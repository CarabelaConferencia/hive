/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.llap.io.decode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.hadoop.hive.llap.Consumer;
import org.apache.hadoop.hive.llap.ConsumerFeedback;
import org.apache.hadoop.hive.llap.io.api.EncodedColumnBatch;
import org.apache.hadoop.hive.llap.io.api.EncodedColumnBatch.StreamBuffer;
import org.apache.hadoop.hive.llap.io.api.impl.ColumnVectorBatch;
import org.apache.hadoop.hive.llap.metrics.LlapDaemonQueueMetrics;

/**
 *
 */
public abstract class EncodedDataConsumer<BatchKey> implements
  Consumer<EncodedColumnBatch<BatchKey>>, ReadPipeline {
  private volatile boolean isStopped = false;
  // TODO: use array, precreate array based on metadata first? Works for ORC. For now keep dumb.
  private final HashMap<BatchKey, EncodedColumnBatch<BatchKey>> pendingData = new HashMap<>();
  private ConsumerFeedback<EncodedColumnBatch.StreamBuffer> upstreamFeedback;
  private final Consumer<ColumnVectorBatch> downstreamConsumer;
  private Callable<Void> readCallable;
  private final int colCount;
  private final LlapDaemonQueueMetrics queueMetrics;

  public EncodedDataConsumer(Consumer<ColumnVectorBatch> consumer, int colCount,
      LlapDaemonQueueMetrics queueMetrics) {
    this.downstreamConsumer = consumer;
    this.colCount = colCount;
    this.queueMetrics = queueMetrics;
  }

  public void init(ConsumerFeedback<EncodedColumnBatch.StreamBuffer> upstreamFeedback,
      Callable<Void> readCallable) {
    this.upstreamFeedback = upstreamFeedback;
    this.readCallable = readCallable;
  }

  @Override
  public Callable<Void> getReadCallable() {
    return readCallable;
  }

  @Override
  public void consumeData(EncodedColumnBatch<BatchKey> data) {
    // TODO: data arrives in whole batches now, not in columns. We could greatly simplify this.
    EncodedColumnBatch<BatchKey> targetBatch = null;
    boolean localIsStopped = false;
    synchronized (pendingData) {
      localIsStopped = isStopped;
      if (!localIsStopped) {
        targetBatch = pendingData.get(data.batchKey);
        if (targetBatch == null) {
          targetBatch = data;
          pendingData.put(data.batchKey, data);
        }
      }
      queueMetrics.setQueueSize(pendingData.size());
    }
    if (localIsStopped) {
      returnProcessed(data.columnData);
      return;
    }

    synchronized (targetBatch) {
      // Check if we are stopped and the batch was already cleaned.
      localIsStopped = (targetBatch.columnData == null);
      if (!localIsStopped) {
        if (targetBatch != data) {
          targetBatch.merge(data);
        }
        if (0 == targetBatch.colsRemaining) {
          synchronized (pendingData) {
            targetBatch = isStopped ? null : pendingData.remove(data.batchKey);
          }
          // Check if we are stopped and the batch had been removed from map.
          localIsStopped = (targetBatch == null);
          // We took the batch out of the map. No more contention with stop possible.
        }
      }
    }
    if (localIsStopped) {
      returnProcessed(data.columnData);
      return;
    }
    if (0 == targetBatch.colsRemaining) {
      long start = System.currentTimeMillis();
      decodeBatch(targetBatch, downstreamConsumer);
      long end = System.currentTimeMillis();
      queueMetrics.addProcessingTime(end - start);
      // Batch has been decoded; unlock the buffers in cache
      returnProcessed(targetBatch.columnData);
    }
  }

  protected abstract void decodeBatch(EncodedColumnBatch<BatchKey> batch,
      Consumer<ColumnVectorBatch> downstreamConsumer);

  protected void returnProcessed(EncodedColumnBatch.StreamBuffer[][] data) {
    for (EncodedColumnBatch.StreamBuffer[] sbs : data) {
      for (EncodedColumnBatch.StreamBuffer sb : sbs) {
        upstreamFeedback.returnData(sb);
      }
    }
  }

  @Override
  public void setDone() {
    synchronized (pendingData) {
      if (!pendingData.isEmpty()) {
        throw new AssertionError("Not all data has been sent downstream: " + pendingData.size());
      }
    }
    downstreamConsumer.setDone();
  }


  @Override
  public void setError(Throwable t) {
    downstreamConsumer.setError(t);
    dicardPendingData(false);
  }

  @Override
  public void returnData(ColumnVectorBatch data) {
    // TODO: column vectors could be added to object pool here
  }

  private void dicardPendingData(boolean isStopped) {
    List<EncodedColumnBatch<BatchKey>> batches = new ArrayList<EncodedColumnBatch<BatchKey>>(
        pendingData.size());
    synchronized (pendingData) {
      if (isStopped) {
        this.isStopped = true;
      }
      batches.addAll(pendingData.values());
      pendingData.clear();
    }
    List<EncodedColumnBatch.StreamBuffer> dataToDiscard = new ArrayList<StreamBuffer>(
        batches.size() * colCount * 2);
    for (EncodedColumnBatch<BatchKey> batch : batches) {
      synchronized (batch) {
        for (EncodedColumnBatch.StreamBuffer[] bb : batch.columnData) {
          for (EncodedColumnBatch.StreamBuffer b : bb) {
            dataToDiscard.add(b);
          }
        }
        batch.columnData = null;
      }
    }
    for (EncodedColumnBatch.StreamBuffer data : dataToDiscard) {
      upstreamFeedback.returnData(data);
    }
  }

  @Override
  public void stop() {
    upstreamFeedback.stop();
    dicardPendingData(true);
  }

  @Override
  public void pause() {
    // We are just a relay; send pause to encoded data producer.
    upstreamFeedback.pause();
  }

  @Override
  public void unpause() {
    // We are just a relay; send unpause to encoded data producer.
    upstreamFeedback.unpause();
  }
}
