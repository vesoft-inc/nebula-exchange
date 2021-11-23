/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange.reader

import com.vesoft.nebula.exchange.config.{KafkaSourceConfigEntry, PulsarSourceConfigEntry}
import org.apache.spark.sql.{DataFrame, SparkSession, Row, Encoders}
import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.spark.sql.types.{StructField, StructType, StringType}
import org.apache.spark.sql.functions.{from_json,col}

/**
  * Spark Streaming
  *
  * @param session
  */
abstract class StreamingBaseReader(override val session: SparkSession) extends Reader {

  override def close(): Unit = {
    session.close()
  }
}

/**
  *
  * @param session
  * @param kafkaConfig
  */
class KafkaReader(override val session: SparkSession,
                  kafkaConfig: KafkaSourceConfigEntry,
                  targetFields: List[String])
    extends StreamingBaseReader(session) {

  require(kafkaConfig.server.trim.nonEmpty && kafkaConfig.topic.trim.nonEmpty && targetFields.nonEmpty)

  override def read(): DataFrame = {
    import session.implicits._
    val fields = targetFields.distinct
    val jsonSchema = StructType(fields.map(field => StructField(field, StringType, true)))
    session.readStream
           .format("kafka")
           .option("kafka.bootstrap.servers", kafkaConfig.server)
           .option("subscribe", kafkaConfig.topic)
           .load()
           .selectExpr("CAST(value AS STRING)")
           .as[(String)]
           .withColumn("value", from_json(col("value"), jsonSchema))
           .select("value.*")
  }
}

/**
  *
  * @param session
  * @param pulsarConfig
  */
class PulsarReader(override val session: SparkSession, pulsarConfig: PulsarSourceConfigEntry)
    extends StreamingBaseReader(session) {

  override def read(): DataFrame = {
    session.readStream
      .format("pulsar")
      .option("service.url", pulsarConfig.serviceUrl)
      .option("admin.url", pulsarConfig.adminUrl)
      .options(pulsarConfig.options)
      .load()
  }
}
