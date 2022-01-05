/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.writer

import com.vesoft.exchange.common.config.{FileBaseSinkConfigEntry, SinkCategory}
import org.apache.spark.sql.{Dataset, Encoders, Row, SparkSession}
import org.junit.Test

class FileBaseWriterSuite {

  @Test
  def writeSstFilesSuite(): Unit = {
    val spark = SparkSession.builder().master("local").getOrCreate()
    import spark.implicits._
    // generate byte[] key using encoder's getVertexKey, space:"test", tag: "person"
    val key1  = "01a40200310000000000000000000000000000000000000002000000" // id: "1"
    val key2  = "01170000320000000000000000000000000000000000000002000000" // id: "2"
    val key3  = "01fe0000330000000000000000000000000000000000000002000000" // id: "3"
    val key4  = "01a90300340000000000000000000000000000000000000002000000" // id: "4"
    val key5  = "01220200350000000000000000000000000000000000000002000000" // id: "5"
    val value = "abc"
    // construct test dataset
    val data: Dataset[(Array[Byte], Array[Byte])] = spark.sparkContext
      .parallelize(
        List(key1.getBytes(), key2.getBytes(), key3.getBytes(), key4.getBytes(), key5.getBytes()))
      .map(line => (line, value.getBytes()))
      .toDF("key", "value")
      .map { row =>
        (row.getAs[Array[Byte]](0), row.getAs[Array[Byte]](1))
      }(Encoders.tuple(Encoders.BINARY, Encoders.BINARY))

    val generateSstFile = new GenerateSstFile

    val fileBaseConfig =
      FileBaseSinkConfigEntry(SinkCategory.SST, "/tmp", "/tmp/remote", None)
    val batchFailure = spark.sparkContext.longAccumulator(s"batchFailure.test}")

    data
      .toDF("key", "value")
      .sortWithinPartitions("key")
      .foreachPartition { iterator: Iterator[Row] =>
        generateSstFile.writeSstFiles(iterator, fileBaseConfig, 10, null, batchFailure)
      }
    assert(batchFailure.value == 0)
  }

}
