/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.utils

import com.vesoft.exchange.common.VidType
import org.apache.spark.Partitioner

class NebulaPartitioner(partitions: Int, vidType: VidType.Value) extends Partitioner {
  require(partitions >= 0, s"Number of partitions ($partitions) cannot be negative.")

  override def numPartitions: Int = partitions

  override def getPartition(key: Any): Int = {
    NebulaUtils.getPartitionId(key.toString, partitions, vidType) - 1
  }
}
