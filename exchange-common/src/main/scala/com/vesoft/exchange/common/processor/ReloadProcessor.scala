/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.processor

import com.vesoft.exchange.common.{ErrorHandler, GraphProvider}
import com.vesoft.exchange.common.GraphProvider
import com.vesoft.exchange.common.config.Configs
import com.vesoft.exchange.common.writer.NebulaGraphClientWriter
import org.apache.log4j.Logger
import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.util.LongAccumulator

import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer

class ReloadProcessor(data: DataFrame,
                      config: Configs,
                      batchSuccess: LongAccumulator,
                      batchFailure: LongAccumulator,
                      recordSuccess: LongAccumulator)
    extends Processor {
  @transient
  private[this] lazy val LOG = Logger.getLogger(this.getClass)

  override def process(): Unit = {
    data.foreachPartition((rows: Iterator[Row]) => processEachPartition(rows))
  }

  private def processEachPartition(iterator: Iterator[Row]): Unit = {
    val graphProvider =
      new GraphProvider(config.databaseConfig.getGraphAddress,
                        config.connectionConfig.timeout,
                        config.sslConfig)

    val writer = new NebulaGraphClientWriter(config.databaseConfig,
                                             config.userConfig,
                                             config.rateConfig,
                                             null,
                                             graphProvider,
                                             config.executionConfig)

    val errorBuffer = ArrayBuffer[String]()

    writer.prepare()
    // batch write
    val startTime = System.currentTimeMillis
    iterator.foreach { row =>
      val ngql          = row.getString(0)
      val failStatement = writer.writeNgql(ngql)
      if (failStatement == null) {
        batchSuccess.add(1)
        recordSuccess.add(1)
      } else {
        errorBuffer.append(failStatement)
        batchFailure.add(1)
      }
    }
    if (errorBuffer.nonEmpty) {
      ErrorHandler.save(
        errorBuffer,
        s"${config.errorConfig.errorPath}/${SparkEnv.get.blockManager.conf.getAppId}/reload.${TaskContext
          .getPartitionId()}")
      errorBuffer.clear()
    }
    LOG.info(s">>>>> data reload in partition ${TaskContext
      .getPartitionId()} cost ${System.currentTimeMillis() - startTime}ms")
    writer.close()
    graphProvider.close()
  }

  /**
    * compute the record amount of ngql
    * @param ngql nebula insert ngql
    */
  private def computeRecordNumber(ngql: String): Int = {
    val substring = ": ("
    var count     = 0
    var index     = 0
    while (index != -1) {
      count += 1
      index = ngql.indexOf(substring, index)
      if (index != (-1)) {
        index += substring.length
      }
    }
    count
  }
}
