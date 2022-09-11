/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange.reader

import com.vesoft.exchange.common.config.{
  ClickHouseConfigEntry,
  HBaseSourceConfigEntry,
  HiveSourceConfigEntry,
  JanusGraphSourceConfigEntry,
  JdbcConfigEntry,
  MaxComputeConfigEntry,
  MySQLSourceConfigEntry,
  Neo4JSourceConfigEntry,
  OracleConfigEntry,
  PostgreSQLSourceConfigEntry,
  ServerDataSourceConfigEntry
}
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.Result
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.util.Bytes
import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DataTypes, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import scala.collection.mutable.ListBuffer

/**
  * ServerBaseReader is the abstract class of
  * It include a spark session and a sentence which will sent to service.
  * @param session
  * @param sentence
  */
abstract class ServerBaseReader(override val session: SparkSession, val sentence: String)
    extends Reader {

  override def close(): Unit = {
    session.close()
  }
}

/**
  * HiveReader extends the @{link ServerBaseReader}.
  * The HiveReader reading data from Apache Hive via sentence.
  * @param session
  * @param hiveConfig
  */
class HiveReader(override val session: SparkSession, hiveConfig: HiveSourceConfigEntry)
    extends ServerBaseReader(session, hiveConfig.sentence) {
  override def read(): DataFrame = {
    session.sql(sentence)
  }
}

/**
  * The MySQLReader extends the ServerBaseReader.
  * The MySQLReader reading data from MySQL via sentence.
  *
  * @param session
  * @param mysqlConfig
  */
class MySQLReader(override val session: SparkSession, mysqlConfig: MySQLSourceConfigEntry)
    extends ServerBaseReader(session, mysqlConfig.sentence) {
  override def read(): DataFrame = {
    val url =
      s"jdbc:mysql://${mysqlConfig.host}:${mysqlConfig.port}/${mysqlConfig.database}?useUnicode=true&characterEncoding=utf-8"
    val df = session.read
      .format("jdbc")
      .option("url", url)
      .option("dbtable", mysqlConfig.table)
      .option("user", mysqlConfig.user)
      .option("password", mysqlConfig.password)
      .load()
    if (sentence != null) {
      df.createOrReplaceTempView(mysqlConfig.table)
      session.sql(sentence)
    } else {
      df
    }
  }
}

/**
  * The PostgreSQLReader
  *
  * @param session
  * @param postgreConfig
  */
class PostgreSQLReader(override val session: SparkSession,
                       postgreConfig: PostgreSQLSourceConfigEntry)
    extends ServerBaseReader(session, postgreConfig.sentence) {
  override def read(): DataFrame = {
    val url =
      s"jdbc:postgresql://${postgreConfig.host}:${postgreConfig.port}/${postgreConfig.database}"
    val df = session.read
      .format("jdbc")
      .option("driver", "org.postgresql.Driver")
      .option("url", url)
      .option("dbtable", postgreConfig.table)
      .option("user", postgreConfig.user)
      .option("password", postgreConfig.password)
      .load()
    if (sentence != null) {
      df.createOrReplaceTempView(postgreConfig.table)
      session.sql(sentence)
    } else {
      df
    }
  }
}

/**
  * Neo4JReader extends the ServerBaseReader
  * this reader support checkpoint by sacrificing performance
  * @param session
  * @param neo4jConfig
  */
class Neo4JReader(override val session: SparkSession, neo4jConfig: Neo4JSourceConfigEntry)
    extends ServerBaseReader(session, neo4jConfig.sentence)
    with CheckPointSupport {

  @transient lazy private val LOG = Logger.getLogger(this.getClass)

  override def read(): DataFrame = {
    throw new UnsupportedOperationException("neo4j datasource is not supported yet for spark 3")
  }
}

/**
  * JanusGraphReader extends the link ServerBaseReader
  * @param session
  * @param janusGraphConfig
  */
class JanusGraphReader(override val session: SparkSession,
                       janusGraphConfig: JanusGraphSourceConfigEntry)
    extends ServerBaseReader(session, "")
    with CheckPointSupport {

  override def read(): DataFrame = {
    throw new UnsupportedOperationException(
      "janusgraph datasource is not supported yet for spark 3")
  }
}

/**
  *
  * @param session
  * @param nebulaConfig
  */
class NebulaReader(override val session: SparkSession, nebulaConfig: ServerDataSourceConfigEntry)
    extends ServerBaseReader(session, nebulaConfig.sentence) {
  override def read(): DataFrame = ???
}

/**
  * HBaseReader extends [[ServerBaseReader]]
  *
  */
class HBaseReader(override val session: SparkSession, hbaseConfig: HBaseSourceConfigEntry)
    extends ServerBaseReader(session, null) {

  private[this] val LOG = Logger.getLogger(this.getClass)

  override def read(): DataFrame = {
    val cf       = hbaseConfig.columnFamily
    val scanConf = HBaseConfiguration.create()
    scanConf.set("hbase.zookeeper.quorum", hbaseConfig.host)
    scanConf.set("hbase.zookeeper.property.clientPort", hbaseConfig.port)
    scanConf.set(TableInputFormat.INPUT_TABLE, hbaseConfig.table)
    hbaseConfig.fields.filter(field => !field.equalsIgnoreCase("rowkey"))
    scanConf.set(TableInputFormat.SCAN_COLUMNS,
                 hbaseConfig.fields
                   .filter(field => !field.equalsIgnoreCase("rowkey"))
                   .map(field => s"$cf:$field")
                   .mkString(" "))
    val fields = hbaseConfig.fields

    val hbaseRDD: RDD[(ImmutableBytesWritable, Result)] = session.sparkContext.newAPIHadoopRDD(
      scanConf,
      classOf[TableInputFormat],
      classOf[ImmutableBytesWritable],
      classOf[Result])

    val rowRDD = hbaseRDD.map(row => {
      val values: ListBuffer[String] = new ListBuffer[String]
      val result: Result             = row._2

      for (i <- fields.indices) {
        if (fields(i).equalsIgnoreCase("rowkey")) {
          values += Bytes.toString(result.getRow)
        } else {
          values += Bytes.toString(result.getValue(Bytes.toBytes(cf), Bytes.toBytes(fields(i))))
        }
      }
      Row.fromSeq(values.toList)
    })
    val schema = StructType(
      fields.map(field => DataTypes.createStructField(field, DataTypes.StringType, true)))
    val dataFrame = session.createDataFrame(rowRDD, schema)
    dataFrame
  }
}

/**
  * MaxCompute Reader
  */
class MaxcomputeReader(override val session: SparkSession, maxComputeConfig: MaxComputeConfigEntry)
    extends ServerBaseReader(session, maxComputeConfig.sentence) {

  override def read(): DataFrame = {
    throw new UnsupportedOperationException(
      "maxcompute datasource is not supported yet for spark 3")
  }
}

/**
  * Clickhouse reader
  */
class ClickhouseReader(override val session: SparkSession,
                       clickHouseConfigEntry: ClickHouseConfigEntry)
    extends ServerBaseReader(session, clickHouseConfigEntry.sentence) {
  Class.forName("ru.yandex.clickhouse.ClickHouseDriver")
  override def read(): DataFrame = {
    val df = session.read
      .format("jdbc")
      .option("driver", "ru.yandex.clickhouse.ClickHouseDriver")
      .option("url", clickHouseConfigEntry.url)
      .option("user", clickHouseConfigEntry.user)
      .option("password", clickHouseConfigEntry.passwd)
      .option("numPartitions", clickHouseConfigEntry.numPartition)
      .option("query", clickHouseConfigEntry.sentence)
      .load()
    df
  }
}

/**
  * Oracle reader
  */
class OracleReader(override val session: SparkSession, oracleConfig: OracleConfigEntry)
    extends ServerBaseReader(session, oracleConfig.sentence) {
  Class.forName(oracleConfig.driver)
  override def read(): DataFrame = {
    var df = session.read
      .format("jdbc")
      .option("url", oracleConfig.url)
      .option("dbtable", oracleConfig.table)
      .option("user", oracleConfig.user)
      .option("password", oracleConfig.passwd)
      .option("driver", oracleConfig.driver)
      .load()

    if (oracleConfig.sentence != null) {
      val tableName = if (oracleConfig.table.contains(".")) {
        oracleConfig.table.split("\\.")(1)
      } else oracleConfig.table
      df.createOrReplaceTempView(tableName)
      df = session.sql(sentence)
    }
    df
  }
}

/**
  * Jdbc reader
  */
class JdbcReader(override val session: SparkSession, jdbcConfig: JdbcConfigEntry)
    extends ServerBaseReader(session, jdbcConfig.sentence) {
  Class.forName(jdbcConfig.driver)
  override def read(): DataFrame = {
    var dfReader = session.read
      .format("jdbc")
      .option("url", jdbcConfig.url)
      .option("dbtable", jdbcConfig.table)
      .option("user", jdbcConfig.user)
      .option("password", jdbcConfig.passwd)
      .option("driver", jdbcConfig.driver)

    if (jdbcConfig.partitionColumn.isDefined) {
      dfReader.option("partitionColumn", jdbcConfig.partitionColumn.get)
    }
    if (jdbcConfig.numPartitions.isDefined) {
      dfReader.option("numPartitions", jdbcConfig.numPartitions.get)
    }
    if (jdbcConfig.lowerBound.isDefined) {
      dfReader.option("lowerBound", jdbcConfig.lowerBound.get)
    }
    if (jdbcConfig.upperBound.isDefined) {
      dfReader.option("upperBound", jdbcConfig.upperBound.get)
    }
    if (jdbcConfig.fetchSize.isDefined) {
      dfReader.option("fetchsize", jdbcConfig.fetchSize.get)
    }

    var df = dfReader.load()

    if (jdbcConfig.sentence != null) {
      val tableName = if (jdbcConfig.table.contains(".")) {
        jdbcConfig.table.split("\\.")(1)
      } else jdbcConfig.table
      df.createOrReplaceTempView(tableName)
      df = session.sql(sentence)
    }
    df
  }
}
