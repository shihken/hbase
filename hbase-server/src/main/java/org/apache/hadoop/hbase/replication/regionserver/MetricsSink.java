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

package org.apache.hadoop.hbase.replication.regionserver;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.CompatibilitySingletonFactory;

/**
 * This class is for maintaining the various replication statistics for a sink and publishing them
 * through the metrics interfaces.
 */
@InterfaceAudience.Private
public class MetricsSink {

  public static final String SINK_AGE_OF_LAST_APPLIED_OP =
    MetricsReplicationSinkSource.SINK_AGE_OF_LAST_APPLIED_OP;
  public static final String SINK_APPLIED_BATCHES =
    MetricsReplicationSinkSource.SINK_APPLIED_BATCHES;
  public static final String SINK_APPLIED_OPS =
    MetricsReplicationSinkSource.SINK_APPLIED_OPS;

  private long lastTimestampForAge = System.currentTimeMillis();
  private final MetricsReplicationSinkSource mss;

  public MetricsSink() {
    mss = CompatibilitySingletonFactory.getInstance(MetricsReplicationSourceFactory.class)
      .getSink();
  }

  /**
   * Set the age of the last applied operation
   *
   * @param timestamp The timestamp of the last operation applied.
   * @return the age that was set
   */
  public long setAgeOfLastAppliedOp(long timestamp) {
    long age = 0;
    if (lastTimestampForAge != timestamp) {
      lastTimestampForAge = timestamp;
      age = System.currentTimeMillis() - lastTimestampForAge;
    } 
    mss.setLastAppliedOpAge(age);
    return age;
  }

  /**
   * Refreshing the age makes sure the value returned is the actual one and
   * not the one set a replication time
   * @return refreshed age
   */
  public long refreshAgeOfLastAppliedOp() {
    return setAgeOfLastAppliedOp(lastTimestampForAge);
  }

  /**
   * Convience method to change metrics when a batch of operations are applied.
   *
   * @param batchSize
   */
  public void applyBatch(long batchSize) {
    mss.incrAppliedBatches(1);
    mss.incrAppliedOps(batchSize);
  }

}
