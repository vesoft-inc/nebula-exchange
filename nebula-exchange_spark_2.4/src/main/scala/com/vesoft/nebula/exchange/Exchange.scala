/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange

import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import java.io.File

import com.vesoft.exchange.Argument
import com.vesoft.exchange.common.{CheckPointHandler, ErrorHandler}
import com.vesoft.exchange.common.config.{
  ClickHouseConfigEntry,
  Configs,
  DataSourceConfigEntry,
  FileBaseSourceConfigEntry,
  HBaseSourceConfigEntry,
  HiveSourceConfigEntry,
  JanusGraphSourceConfigEntry,
  JdbcConfigEntry,
  KafkaSourceConfigEntry,
  MaxComputeConfigEntry,
  MySQLSourceConfigEntry,
  Neo4JSourceConfigEntry,
  OracleConfigEntry,
  PostgreSQLSourceConfigEntry,
  PulsarSourceConfigEntry,
  SinkCategory,
  SourceCategory,
  UdfConfigEntry
}
import com.vesoft.nebula.exchange.reader.{
  CSVReader,
  ClickhouseReader,
  HBaseReader,
  HiveReader,
  JSONReader,
  JanusGraphReader,
  JdbcReader,
  KafkaReader,
  MaxcomputeReader,
  MySQLReader,
  Neo4JReader,
  ORCReader,
  OracleReader,
  ParquetReader,
  PostgreSQLReader,
  PulsarReader
}
import com.vesoft.exchange.common.processor.ReloadProcessor
import com.vesoft.exchange.common.utils.SparkValidate
import com.vesoft.nebula.exchange.processor.{EdgeProcessor, VerticesProcessor}
import org.apache.log4j.Logger
import org.apache.spark.sql.functions.{col, concat_ws}
import org.apache.spark.sql.types.StringType
import org.apache.spark.{SparkConf, SparkEnv}

import scala.collection.mutable.ListBuffer

final case class TooManyErrorsException(private val message: String) extends Exception(message)

/**
  * SparkClientGenerator is a simple spark job used to write data into Nebula Graph parallel.
  */
object Exchange {
  private[this] val LOG = Logger.getLogger(this.getClass)

  def main(args: Array[String]): Unit = {
    val PROGRAM_NAME = "Nebula Graph Exchange"
    val options      = Configs.parser(args, PROGRAM_NAME)
    val c: Argument = options match {
      case Some(config) => config
      case _ =>
        LOG.error(">>>>> Argument parse failed")
        sys.exit(-1)
    }

    val configs = Configs.parse(c.config, c.variable, c.param)
    LOG.info(s">>>>> Config ${configs}")

    val session = SparkSession
      .builder()
      .appName(PROGRAM_NAME)
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")

    for (key <- configs.sparkConfigEntry.map.keySet) {
      session.config(key, configs.sparkConfigEntry.map(key))
    }

    val sparkConf = new SparkConf()
    sparkConf.registerKryoClasses(Array(classOf[com.facebook.thrift.async.TAsyncClientManager]))

    // com.vesoft.exchange.common.config hive for sparkSession
    if (c.hive) {
      if (configs.hiveConfigEntry.isEmpty) {
        LOG.info(
          ">>>>> you don't com.vesoft.exchange.common.config hive source, so using hive tied with spark.")
      } else {
        val hiveConfig = configs.hiveConfigEntry.get
        sparkConf.set("spark.sql.warehouse.dir", hiveConfig.warehouse)
        sparkConf
          .set("javax.jdo.option.ConnectionURL", hiveConfig.connectionURL)
          .set("javax.jdo.option.ConnectionDriverName", hiveConfig.connectionDriverName)
          .set("javax.jdo.option.ConnectionUserName", hiveConfig.connectionUserName)
          .set("javax.jdo.option.ConnectionPassword", hiveConfig.connectionPassWord)
      }
    }

    session.config(sparkConf)

    if (c.hive) {
      session.enableHiveSupport()
    }

    val spark = session.getOrCreate()
    // check the spark version
    SparkValidate.validate(spark.version, "2.4.*")
    val startTime                      = System.currentTimeMillis()
    var totalClientBatchSuccess: Long  = 0L
    var totalClientBatchFailure: Long  = 0L
    var totalClientRecordSuccess: Long = 0L
    var totalClientRecordFailure: Long = 0L
    var totalSstRecordSuccess: Long    = 0l
    var totalSstRecordFailure: Long    = 0L

    // reload for failed import tasks
    if (c.reload.nonEmpty) {
      val batchSuccess  = spark.sparkContext.longAccumulator(s"batchSuccess.reload")
      val batchFailure  = spark.sparkContext.longAccumulator(s"batchFailure.reload")
      val recordSuccess = spark.sparkContext.longAccumulator(s"recordSuccess.reimport")

      val start     = System.currentTimeMillis()
      val data      = spark.read.text(c.reload)
      val processor = new ReloadProcessor(data, configs, batchSuccess, batchFailure, recordSuccess)
      processor.process()
      LOG.info(s">>>>> batchSuccess.reload: ${batchSuccess.value}")
      LOG.info(s">>>>> batchFailure.reload: ${batchFailure.value}")
      LOG.info(s">>>>> recordSuccess.reload: ${recordSuccess.value}")
      LOG.info(
        s">>>>> exchange reload job finished, cost:${((System.currentTimeMillis() - start) / 1000.0)
          .formatted("%.2f")}s")
      sys.exit(0)
    }

    // record the failed batch number
    var failures: Long = 0L

    // import tags
    if (configs.tagsConfig.nonEmpty) {
      for (tagConfig <- configs.tagsConfig) {
        LOG.info(s">>>>> Processing Tag ${tagConfig.name}")
        spark.sparkContext.setJobGroup(tagConfig.name, s"Tag: ${tagConfig.name}")

        val start = System.currentTimeMillis()

        val fieldKeys = tagConfig.fields
        LOG.info(s">>>>> field keys: ${fieldKeys.mkString(", ")}")
        val nebulaKeys = tagConfig.nebulaFields
        LOG.info(s">>>>> nebula keys: ${nebulaKeys.mkString(", ")}")

        val fields = tagConfig.vertexField :: tagConfig.fields
        val data   = createDataSource(spark, tagConfig.dataSourceConfigEntry, fields)
        if (data.isDefined && c.dry && !data.get.isStreaming) {
          data.get.show(truncate = false)
        }
        if (data.isDefined && !c.dry) {
          val df = if (tagConfig.vertexUdf.isDefined) {
            dataUdf(data.get, tagConfig.vertexUdf.get)
          } else {
            data.get
          }

          val batchSuccess =
            spark.sparkContext.longAccumulator(s"batchSuccess.${tagConfig.name}")
          val batchFailure =
            spark.sparkContext.longAccumulator(s"batchFailure.${tagConfig.name}")
          val recordSuccess = spark.sparkContext.longAccumulator(s"recordSuccess.${tagConfig.name}")
          val recordFailure = spark.sparkContext.longAccumulator(s"recordFailure.${tagConfig.name}")

          val processor = new VerticesProcessor(
            spark,
            repartition(df, tagConfig.partition, tagConfig.dataSourceConfigEntry.category),
            tagConfig,
            fieldKeys,
            nebulaKeys,
            configs,
            batchSuccess,
            batchFailure,
            recordSuccess,
            recordFailure
          )
          processor.process()
          val costTime = ((System.currentTimeMillis() - start) / 1000.0).formatted("%.2f")
          LOG.info(s">>>>> import for tag ${tagConfig.name}, cost time: ${costTime}s")
          if (tagConfig.dataSinkConfigEntry.category == SinkCategory.CLIENT) {
            LOG.info(s">>>>> Client-Import: batchSuccess.${tagConfig.name}: ${batchSuccess.value}")
            LOG.info(
              s">>>>> Client-Import: recordSuccess.${tagConfig.name}: ${recordSuccess.value}")
            LOG.info(s">>>>> Client-Import: batchFailure.${tagConfig.name}: ${batchFailure.value}")
            LOG.info(
              s">>>>> Client-Import: recordFailure.${tagConfig.name}: ${recordFailure.value}")
            failures += batchFailure.value
            totalClientRecordSuccess += recordSuccess.value
            totalClientRecordFailure += recordFailure.value
            totalClientBatchSuccess += batchSuccess.value
            totalClientBatchFailure += batchFailure.value
          } else {
            LOG.info(s">>>>> SST-Import: success.${tagConfig.name}: ${recordSuccess.value}")
            LOG.info(s">>>>> SST-Import: failure.${tagConfig.name}: ${recordFailure.value}")
            totalSstRecordSuccess += recordSuccess.value
            totalSstRecordFailure += recordFailure.value
          }
        }
      }
    } else {
      LOG.warn(">>>>>> Tag is not defined")
    }

    // import edges
    if (configs.edgesConfig.nonEmpty) {
      for (edgeConfig <- configs.edgesConfig) {
        LOG.info(s">>>>> Processing Edge ${edgeConfig.name}")
        spark.sparkContext.setJobGroup(edgeConfig.name, s"Edge: ${edgeConfig.name}")

        val start = System.currentTimeMillis()

        val fieldKeys = edgeConfig.fields
        LOG.info(s">>>>> field keys: ${fieldKeys.mkString(", ")}")
        val nebulaKeys = edgeConfig.nebulaFields
        LOG.info(s">>>>> nebula keys: ${nebulaKeys.mkString(", ")}")
        val fields = if (edgeConfig.rankingField.isDefined) {
          edgeConfig.rankingField.get :: edgeConfig.sourceField :: edgeConfig.targetField :: edgeConfig.fields
        } else {
          edgeConfig.sourceField :: edgeConfig.targetField :: edgeConfig.fields
        }
        val data = createDataSource(spark, edgeConfig.dataSourceConfigEntry, fields)
        if (data.isDefined && c.dry && !data.get.isStreaming) {
          data.get.show(truncate = false)
        }
        if (data.isDefined && !c.dry) {
          var df = data.get
          if (edgeConfig.srcVertexUdf.isDefined) {
            df = dataUdf(df, edgeConfig.srcVertexUdf.get)
          }
          if (edgeConfig.dstVertexUdf.isDefined) {
            df = dataUdf(df, edgeConfig.dstVertexUdf.get)
          }

          val batchSuccess = spark.sparkContext.longAccumulator(s"batchSuccess.${edgeConfig.name}")
          val batchFailure = spark.sparkContext.longAccumulator(s"batchFailure.${edgeConfig.name}")
          val recordSuccess =
            spark.sparkContext.longAccumulator(s"recordSuccess.${edgeConfig.name}")
          val recordFailure =
            spark.sparkContext.longAccumulator(s"recordFailure.${edgeConfig.name}")

          val processor = new EdgeProcessor(
            spark,
            repartition(df, edgeConfig.partition, edgeConfig.dataSourceConfigEntry.category),
            edgeConfig,
            fieldKeys,
            nebulaKeys,
            configs,
            batchSuccess,
            batchFailure,
            recordSuccess,
            recordFailure
          )
          processor.process()
          val costTime = ((System.currentTimeMillis() - start) / 1000.0).formatted("%.2f")
          LOG.info(s">>>>> import for edge ${edgeConfig.name}, cost time: ${costTime}s")
          if (edgeConfig.dataSinkConfigEntry.category == SinkCategory.CLIENT) {
            LOG.info(s">>>>> Client-Import: batchSuccess.${edgeConfig.name}: ${batchSuccess.value}")
            LOG.info(
              s">>>>> Client-Import: recordSuccess.${edgeConfig.name}: ${recordSuccess.value}")
            LOG.info(s">>>>> Client-Import: batchFailure.${edgeConfig.name}: ${batchFailure.value}")
            LOG.info(
              s">>>>> Client-Import: recordFailure.${edgeConfig.name}: ${recordFailure.value}")
            failures += batchFailure.value
            totalClientRecordSuccess += recordSuccess.value
            totalClientRecordFailure += recordFailure.value
            totalClientBatchSuccess += batchSuccess.value
            totalClientBatchFailure += batchFailure.value
          } else {
            LOG.info(s">>>>> SST-Import: failure.${edgeConfig.name}: ${recordFailure.value}")
            totalSstRecordSuccess += recordSuccess.value
            totalSstRecordFailure += recordFailure.value
          }
        }
      }
    } else {
      LOG.warn(">>>>> Edge is not defined")
    }

    // reimport for failed tags and edges
    val errorPath = s"${configs.errorConfig.errorPath}/${SparkEnv.get.blockManager.conf.getAppId}"
    if (failures > 0 && ErrorHandler.existError(errorPath)) {
      spark.sparkContext.setJobGroup("Reload", s"Reload: ${errorPath}")
      val start         = System.currentTimeMillis()
      val batchSuccess  = spark.sparkContext.longAccumulator(s"batchSuccess.reimport")
      val batchFailure  = spark.sparkContext.longAccumulator(s"batchFailure.reimport")
      val recordSuccess = spark.sparkContext.longAccumulator(s"recordSuccess.reimport")
      val data          = spark.read.text(errorPath)
      val processor     = new ReloadProcessor(data, configs, batchSuccess, batchFailure, recordSuccess)
      processor.process()
      val costTime = ((System.currentTimeMillis() - start) / 1000.0).formatted("%.2f")
      LOG.info(s">>>>> reimport ngql cost time: ${costTime}")
      LOG.info(s">>>>> batchSuccess.reimport: ${batchSuccess.value}")
      LOG.info(s">>>>> batchFailure.reimport: ${batchFailure.value}")
      LOG.info(s">>>>> recordSuccess.reimport: ${recordSuccess.value}")
      totalClientBatchSuccess += batchSuccess.value
      totalClientBatchFailure -= batchSuccess.value
      totalClientRecordSuccess += recordSuccess.value
      totalClientRecordFailure -= recordSuccess.value
      if (totalClientRecordFailure < 0) {
        totalClientRecordFailure = 0
      }
    }
    spark.close()
    LOG.info(
      s"\n>>>>>> exchange job finished, cost ${((System.currentTimeMillis() - startTime) / 1000.0)
        .formatted("%.2f")}s \n" +
        s">>>>>> total client batchSuccess:${totalClientBatchSuccess} \n" +
        s">>>>>> total client recordsSuccess:${totalClientRecordSuccess} \n" +
        s">>>>>> total client batchFailure:${totalClientBatchFailure} \n" +
        s">>>>>> total client recordsFailure:${totalClientRecordFailure} \n" +
        s">>>>>> total SST failure:${totalSstRecordFailure} \n" +
        s">>>>>> total SST Success:${totalSstRecordSuccess}")
  }

  /**
    * Create data source for different data type.
    *
    * @param session The Spark Session.
    * @param config  The com.vesoft.exchange.common.config.
    * @return
    */
  private[this] def createDataSource(
      session: SparkSession,
      config: DataSourceConfigEntry,
      fields: List[String]
  ): Option[DataFrame] = {
    config.category match {
      case SourceCategory.PARQUET =>
        val parquetConfig = config.asInstanceOf[FileBaseSourceConfigEntry]
        LOG.info(s""">>>>> Loading Parquet files from ${parquetConfig.path}""")
        val reader = new ParquetReader(session, parquetConfig)
        Some(reader.read())
      case SourceCategory.ORC =>
        val orcConfig = config.asInstanceOf[FileBaseSourceConfigEntry]
        LOG.info(s""">>>>> Loading ORC files from ${orcConfig.path}""")
        val reader = new ORCReader(session, orcConfig)
        Some(reader.read())
      case SourceCategory.JSON =>
        val jsonConfig = config.asInstanceOf[FileBaseSourceConfigEntry]
        LOG.info(s""">>>>> Loading JSON files from ${jsonConfig.path}""")
        val reader = new JSONReader(session, jsonConfig)
        Some(reader.read())
      case SourceCategory.CSV =>
        val csvConfig = config.asInstanceOf[FileBaseSourceConfigEntry]
        LOG.info(s""">>>>> Loading CSV files from ${csvConfig.path}""")
        val reader =
          new CSVReader(session, csvConfig)
        Some(reader.read())
      case SourceCategory.HIVE =>
        val hiveConfig = config.asInstanceOf[HiveSourceConfigEntry]
        LOG.info(s""">>>>> Loading from Hive and exec ${hiveConfig.sentence}""")
        val reader = new HiveReader(session, hiveConfig)
        Some(reader.read())
      case SourceCategory.KAFKA => {
        val kafkaConfig = config.asInstanceOf[KafkaSourceConfigEntry]
        LOG.info(
          s""">>>>> Loading from Kafka ${kafkaConfig.server} and subscribe ${kafkaConfig.topic}""")
        val reader = new KafkaReader(session, kafkaConfig, fields)
        Some(reader.read())
      }
      case SourceCategory.NEO4J =>
        val neo4jConfig = config.asInstanceOf[Neo4JSourceConfigEntry]
        LOG.info(s">>>>> Loading from neo4j com.vesoft.exchange.common.config: ${neo4jConfig}")
        val reader = new Neo4JReader(session, neo4jConfig)
        Some(reader.read())
      case SourceCategory.MYSQL =>
        val mysqlConfig = config.asInstanceOf[MySQLSourceConfigEntry]
        LOG.info(s">>>>> Loading from mysql com.vesoft.exchange.common.config: ${mysqlConfig}")
        val reader = new MySQLReader(session, mysqlConfig)
        Some(reader.read())
      case SourceCategory.POSTGRESQL =>
        val postgreConfig = config.asInstanceOf[PostgreSQLSourceConfigEntry]
        LOG.info(
          s">>>>> Loading from postgresql com.vesoft.exchange.common.config: ${postgreConfig}")
        val reader = new PostgreSQLReader(session, postgreConfig)
        Some(reader.read())
      case SourceCategory.PULSAR =>
        val pulsarConfig = config.asInstanceOf[PulsarSourceConfigEntry]
        LOG.info(s">>>>> Loading from pulsar com.vesoft.exchange.common.config: ${pulsarConfig}")
        val reader = new PulsarReader(session, pulsarConfig)
        Some(reader.read())
      case SourceCategory.JANUS_GRAPH =>
        val janusGraphSourceConfigEntry = config.asInstanceOf[JanusGraphSourceConfigEntry]
        val reader                      = new JanusGraphReader(session, janusGraphSourceConfigEntry)
        Some(reader.read())
      case SourceCategory.HBASE =>
        val hbaseSourceConfigEntry = config.asInstanceOf[HBaseSourceConfigEntry]
        val reader                 = new HBaseReader(session, hbaseSourceConfigEntry)
        Some(reader.read())
      case SourceCategory.MAXCOMPUTE =>
        val maxComputeConfigEntry = config.asInstanceOf[MaxComputeConfigEntry]
        val reader                = new MaxcomputeReader(session, maxComputeConfigEntry)
        Some(reader.read())
      case SourceCategory.CLICKHOUSE => {
        val clickhouseConfigEntry = config.asInstanceOf[ClickHouseConfigEntry]
        val reader                = new ClickhouseReader(session, clickhouseConfigEntry)
        Some(reader.read())
      }
      case SourceCategory.ORACLE => {
        val oracleConfig = config.asInstanceOf[OracleConfigEntry]
        val reader       = new OracleReader(session, oracleConfig)
        Some(reader.read())
      }
      case SourceCategory.JDBC => {
        val jdbcConfig = config.asInstanceOf[JdbcConfigEntry]
        val reader     = new JdbcReader(session, jdbcConfig)
        Some(reader.read())
      }
      case _ => {
        LOG.error(s">>>>> Data source ${config.category} not supported")
        None
      }
    }
  }

  /**
    * Repartition the data frame using the specified partition number.
    *
    * @param frame
    * @param partition
    * @return
    */
  private[this] def repartition(frame: DataFrame,
                                partition: Int,
                                sourceCategory: SourceCategory.Value): DataFrame = {
    if (partition > 0 && !CheckPointHandler.checkSupportResume(sourceCategory)) {
      frame.repartition(partition).toDF
    } else {
      frame
    }
  }

  private[this] def dataUdf(data: DataFrame, udfConfig: UdfConfigEntry): DataFrame = {
    val oldCols                           = udfConfig.oldColNames
    val sep                               = udfConfig.sep
    val newCol                            = udfConfig.newColName
    val originalFieldsNames               = data.schema.fieldNames.toList
    val finalColNames: ListBuffer[Column] = new ListBuffer[Column]
    for (field <- originalFieldsNames) {
      finalColNames.append(col(field))
    }
    finalColNames.append(concat_ws(sep, oldCols.map(c => col(c)): _*).cast(StringType).as(newCol))
    data.select(finalColNames: _*)
  }
}
