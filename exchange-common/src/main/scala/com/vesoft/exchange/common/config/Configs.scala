/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.config

import java.io.{File, InputStreamReader}
import java.nio.file.Files
import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigFactory}
import com.vesoft.exchange.Argument
import com.vesoft.exchange.common.plugin.DataSourcePlugin
import com.vesoft.exchange.common.{KeyPolicy, PasswordEncryption}
import com.vesoft.exchange.common.utils.NebulaUtils
import com.vesoft.nebula.client.graph.data.HostAddress
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FileSystem, Path}
import org.apache.log4j.Logger

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._
import scala.util.control.Breaks.break

object Type extends Enumeration {
  type Type = Value
  val VERTEX = Value("VERTEX")
  val EDGE   = Value("EDGE")
}

object SslType extends Enumeration {
  type SslType = Value
  val CA   = Value("ca")
  val SELF = Value("self")
}

object WriteMode extends Enumeration {
  type Mode = Value
  val INSERT = Value("insert")
  val UPDATE = Value("update")
  val DELETE = Value("delete")
}

/**
  * DataBaseConfigEntry describe the nebula cluster's address and which space will be used.
  *
  * @param graphAddress
  * @param space
  */
case class DataBaseConfigEntry(graphAddress: List[String],
                               space: String,
                               metaAddresses: List[String]) {
  require(graphAddress.nonEmpty, "nebula.address.graph cannot be empty")
  require(metaAddresses.nonEmpty, "nebula.address.meta cannot be empty")
  require(space.trim.nonEmpty, "nebula.space cannot be empty")

  for (address <- graphAddress) {
    require(
      !address.contains(",") && !address.contains("，"),
      "nebula.address.graph has wrong format, please make sure the format is [\"ip1:port1\",\"ip2:port2\"]")
  }
  for (address <- metaAddresses) {
    require(
      !address.contains(",") && !address.contains("，"),
      "nebula.address.meta has wrong format, please make sure the format is [\"ip1:port1\",\"ip2:port2\"]")
  }

  override def toString: String = {
    s"DataBaseConfigEntry:{graphAddress:$graphAddress, space:$space, metaAddress:$metaAddresses}"
  }

  def getGraphAddress: List[HostAddress] = {
    val hostAndPorts = new ListBuffer[HostAddress]
    for (address <- graphAddress) {
      hostAndPorts.append(NebulaUtils.getAddressFromString(address))
    }
    hostAndPorts.toList
  }

  def getMetaAddress: List[HostAddress] = {
    val hostAndPorts = new ListBuffer[HostAddress]
    for (address <- metaAddresses) {
      hostAndPorts.append(NebulaUtils.getAddressFromString(address))
    }
    hostAndPorts.toList
  }
}

/**
  * UserConfigEntry is used when the client login the nebula graph service.
  *
  * @param user
  * @param password
  */
case class UserConfigEntry(user: String, password: String) {
  require(user.trim.nonEmpty && password.trim.nonEmpty, "user and password cannot be empty.")

  override def toString: String =
    s"UserConfigEntry{user:$user, password:xxxxx}"
}

/**
  * ConnectionConfigEntry
  *
  * @param timeout
  * @param retry
  */
case class ConnectionConfigEntry(timeout: Int, retry: Int) {
  require(timeout > 0 && retry > 0, "connection timeout or retry must be larger than 0")

  override def toString: String = s"ConnectionConfigEntry:{timeout:$timeout, retry:$retry}"
}

/**
  * ExecutionConfigEntry
  *
  * @param timeout
  * @param retry
  * @param interval
  */
case class ExecutionConfigEntry(timeout: Int, retry: Int, interval: Int) {
  require(timeout > 0, "execution timeout must be larger than 0")

  override def toString: String = s"ExecutionConfigEntry:{timeout:$timeout, retry:$retry}"
}

/**
  * ErrorConfigEntry
  *
  * @param errorPath
  * @param errorMaxSize
  */
case class ErrorConfigEntry(errorPath: String, errorMaxSize: Int) {
  require(errorPath.trim.nonEmpty && errorMaxSize >= 0,
          "errorPath cannot be empty, and error maxSize cannot be less than 0")

  override def toString: String =
    s"ErrorConfigEntry:{errorPath:$errorPath, errorMaxSize:$errorMaxSize}"
}

/**
  * RateConfigEntry
  *
  * @param limit
  * @param timeout
  */
case class RateConfigEntry(limit: Int, timeout: Int) {
  require(limit > 0, "rate limit must be larger than 0")

  override def toString: String = s"RateConfigEntry:{limit:$limit, timeout:$timeout}"
}

/**
  * SslConfigEntry
  */
case class SslConfigEntry(enableGraph: Boolean,
                          enableMeta: Boolean,
                          signType: SslType.Value,
                          caSignParam: CaSignParam,
                          selfSignParam: SelfSignParam) {
  if (enableGraph || enableMeta) {
    signType match {
      case SslType.CA =>
        require(
          caSignParam != null && !caSignParam.caCrtFilePath.isEmpty
            && !caSignParam.crtFilePath.isEmpty && !caSignParam.keyFilePath.isEmpty)
      case SslType.SELF =>
        require(
          selfSignParam != null && !selfSignParam.crtFilePath.isEmpty
            && !selfSignParam.keyFilePath.isEmpty && !selfSignParam.password.isEmpty)
      case _ => None
    }
  }

  override def toString: String =
    s"SslConfigEntry:{enableGraph:$enableGraph, enableMeta:$enableMeta, signType:${signType.toString}}"
}

case class CaSignParam(caCrtFilePath: String, crtFilePath: String, keyFilePath: String)

case class SelfSignParam(crtFilePath: String, keyFilePath: String, password: String)

case class UdfConfigEntry(sep: String, oldColNames: List[String], newColName: String) {
  override def toString(): String = {
    s"sep:$sep, oldColNames: $oldColNames, newColName: $newColName"
  }
}

case class FilterConfigEntry(filter: String){
  override def toString(): String = s"filter:$filter"
}

/**
  *
  */
object SparkConfigEntry {
  def apply(config: Config): SparkConfigEntry = {
    val map         = mutable.Map[String, String]()
    val sparkConfig = config.getObject("spark")
    for (key <- sparkConfig.unwrapped().keySet().asScala) {
      val sparkKey = s"spark.${key}"
      if (config.getAnyRef(sparkKey).isInstanceOf[String]) {
        val sparkValue = config.getString(sparkKey)
        map += sparkKey -> sparkValue
      } else {
        for (subKey <- config.getObject(sparkKey).unwrapped().keySet().asScala) {
          val key        = s"${sparkKey}.${subKey}"
          val sparkValue = config.getString(key)
          map += key -> sparkValue
        }
      }
    }
    SparkConfigEntry(map.toMap)
  }
}

/**
  * SparkConfigEntry support key-value pairs for spark session.
  *
  * @param map
  */
case class SparkConfigEntry(map: Map[String, String]) {
  override def toString: String = {
    ""
  }
}

case class HiveConfigEntry(warehouse: String,
                           connectionURL: String,
                           connectionDriverName: String,
                           connectionUserName: String,
                           connectionPassWord: String) {
  override def toString: String =
    s"HiveConfigEntry:{" +
      s"warehouse=$warehouse, " +
      s"connectionURL=$connectionURL, " +
      s"connectionDriverName=$connectionDriverName, " +
      s"connectionUserName=$connectionUserName, " +
      s"connectionPassWord=$connectionPassWord}"
}

/**
  * Configs
  */
case class Configs(databaseConfig: DataBaseConfigEntry,
                   userConfig: UserConfigEntry,
                   connectionConfig: ConnectionConfigEntry,
                   executionConfig: ExecutionConfigEntry,
                   errorConfig: ErrorConfigEntry,
                   rateConfig: RateConfigEntry,
                   sslConfig: SslConfigEntry,
                   sparkConfigEntry: SparkConfigEntry,
                   tagsConfig: List[TagConfigEntry],
                   edgesConfig: List[EdgeConfigEntry],
                   hiveConfigEntry: Option[HiveConfigEntry] = None)

object Configs {
  private[this] val LOG = Logger.getLogger(this.getClass)

  private[this] val DEFAULT_CONNECTION_TIMEOUT    = Integer.MAX_VALUE
  private[this] val DEFAULT_CONNECTION_RETRY      = 3
  private[this] val DEFAULT_EXECUTION_RETRY       = 3
  private[this] val DEFAULT_EXECUTION_TIMEOUT     = Integer.MAX_VALUE
  private[this] val DEFAULT_EXECUTION_INTERVAL    = 0
  private[this] val DEFAULT_ERROR_OUTPUT_PATH     = "file:///tmp/nebula.writer.errors/"
  private[this] val DEFAULT_ERROR_MAX_BATCH_SIZE  = Int.MaxValue
  private[this] val DEFAULT_RATE_LIMIT            = 1024
  private[this] val DEFAULT_RATE_TIMEOUT          = 100
  private[this] val DEFAULT_ENABLE_SSL            = false
  private[this] val DEFAULT_SSL_SIGN_TYPE         = "CA"
  private[this] val DEFAULT_EDGE_RANKING          = 0L
  private[this] val DEFAULT_BATCH                 = 500
  private[this] val DEFAULT_PARTITION             = -1
  private[this] val DEFAULT_CHECK_POINT_PATH      = None
  private[this] val DEFAULT_LOCAL_PATH            = None
  private[this] val DEFAULT_REMOTE_PATH           = None
  private[this] val DEFAULT_STREAM_INTERVAL       = 30
  private[this] val DEFAULT_KAFKA_STARTINGOFFSETS = "latest"
  private[this] val DEFAULT_PARALLEL              = 1
  private[this] val DEFAULT_WRITE_MODE            = "INSERT"

  /**
    *
    * @param configPath
    * @return
    */
  def parse(configPath: String, variable: Boolean = false, param: String = ""): Configs = {
    var config: Config             = null
    var paths: Map[String, String] = null
    if (variable) {
      if (param.isEmpty) throw new IllegalArgumentException(s"-p must to set ")
      paths = param
        .split(",")
        .map(path => {
          val kv = path.split("=")
          (kv(0), kv(1))
        })
        .toMap
    }
    if (configPath.startsWith("hdfs://")) {
      val hadoopConfig: Configuration = new Configuration()
      val fs: FileSystem              = org.apache.hadoop.fs.FileSystem.get(hadoopConfig)
      val file: FSDataInputStream     = fs.open(new Path(configPath))
      val reader                      = new InputStreamReader(file)
      config = ConfigFactory.parseReader(reader)
    } else {
      if (!Files.exists(new File(configPath).toPath)) {
        throw new IllegalArgumentException(s"${configPath} not exist")
      }
      config = ConfigFactory.parseFile(new File(configPath))
    }

    val nebulaConfig  = config.getConfig("nebula")
    val addresses     = nebulaConfig.getStringList("address.graph").asScala.toList
    val metaAddresses = nebulaConfig.getStringList("address.meta").asScala.toList

    val space         = nebulaConfig.getString("space")
    val databaseEntry = DataBaseConfigEntry(addresses, space, metaAddresses)
    val enableTagless = getOrElse(nebulaConfig, "enableTagless", false)
    LOG.info(s"DataBase Config ${databaseEntry}")

    val user      = nebulaConfig.getString("user")
    val pswd      = nebulaConfig.getString("pswd")
    val enableRSA = getOrElse(nebulaConfig, "enableRSA", false)
    val privateKey = getStringOrNull(nebulaConfig, "privateKey")
    require(!enableRSA || privateKey != null, "enableRSA is true, privateKey cannot be empty.")
    var userEntry = UserConfigEntry(user, pswd)
    if (enableRSA) {
      userEntry = UserConfigEntry(user, PasswordEncryption.decryptPassword(pswd, privateKey))
    }

    LOG.info(s"User Config ${userEntry}")

    val connectionConfig  = getConfigOrNone(nebulaConfig, "connection")
    val connectionTimeout = getOrElse(connectionConfig, "timeout", DEFAULT_CONNECTION_TIMEOUT)
    val connectionRetry   = getOrElse(connectionConfig, "retry", DEFAULT_CONNECTION_RETRY)
    val connectionEntry   = ConnectionConfigEntry(connectionTimeout, connectionRetry)
    LOG.info(s"Connection Config ${connectionConfig}")

    val executionConfig   = getConfigOrNone(nebulaConfig, "execution")
    val executionTimeout  = getOrElse(executionConfig, "timeout", DEFAULT_EXECUTION_TIMEOUT)
    val executionRetry    = getOrElse(executionConfig, "retry", DEFAULT_EXECUTION_RETRY)
    val executionInterval = getOrElse(executionConfig, "interval", DEFAULT_EXECUTION_INTERVAL)
    val executionEntry    = ExecutionConfigEntry(executionTimeout, executionRetry, executionInterval)
    LOG.info(s"Execution Config ${executionEntry}")

    val errorConfig = getConfigOrNone(nebulaConfig, "error")
    var errorPath   = getOrElse(errorConfig, "output", DEFAULT_ERROR_OUTPUT_PATH)
    if (!errorPath.startsWith("hdfs://")) {
      if (!errorPath.startsWith("file://")) {
        errorPath = s"file://${errorPath}"
      }
    }

    val errorMaxSize = getOrElse(errorConfig, "max", DEFAULT_ERROR_MAX_BATCH_SIZE)
    val errorEntry   = ErrorConfigEntry(errorPath, errorMaxSize)

    val rateConfig  = getConfigOrNone(nebulaConfig, "rate")
    val rateLimit   = getOrElse(rateConfig, "limit", DEFAULT_RATE_LIMIT)
    val rateTimeout = getOrElse(rateConfig, "timeout", DEFAULT_RATE_TIMEOUT)
    val rateEntry   = RateConfigEntry(rateLimit, rateTimeout)

    val sslConfig   = getConfigOrNone(nebulaConfig, "ssl")
    val enableGraph = getOrElse(sslConfig, "enable.graph", DEFAULT_ENABLE_SSL)
    val enableMeta  = getOrElse(sslConfig, "enable.meta", DEFAULT_ENABLE_SSL)
    val signType =
      SslType.withName(getOrElse(sslConfig, "sign.type", DEFAULT_SSL_SIGN_TYPE).toLowerCase)
    var caParam: CaSignParam     = null
    var selfParam: SelfSignParam = null
    if (enableGraph || enableMeta) {
      if (signType == SslType.CA) {
        val caCrtPath = sslConfig.get.getString("ca.param.caCrtFilePath")
        val crtPath   = sslConfig.get.getString("ca.param.crtFilePath")
        val keyPath   = sslConfig.get.getString("ca.param.keyFilePath")
        caParam = CaSignParam(caCrtPath, crtPath, keyPath)
      } else if (signType == SslType.SELF) {
        val crtPath  = sslConfig.get.getString("self.param.crtFilePath")
        val keyPath  = sslConfig.get.getString("self.param.keyFilePath")
        val password = sslConfig.get.getString("self.param.password")
        selfParam = SelfSignParam(crtPath, keyPath, password)
      } else {
        throw new IllegalArgumentException(
          "ssl.sign.type is not supported, only support CA and SELF")
      }
    }
    val sslEntry = SslConfigEntry(enableGraph, enableMeta, signType, caParam, selfParam)

    val sparkEntry = SparkConfigEntry(config)

    var hiveEntryOpt: Option[HiveConfigEntry] = None
    if (config.hasPath("hive")) {
      val warehouse            = config.getString("hive.warehouse")
      val connectionURL        = config.getString("hive.connectionURL")
      val connectionDriverName = config.getString("hive.connectionDriverName")
      val connectionUserName   = config.getString("hive.connectionUserName")
      val connectionPassword   = config.getString("hive.connectionPassword")
      val hiveEntry = HiveConfigEntry(warehouse,
                                      connectionURL,
                                      connectionDriverName,
                                      connectionUserName,
                                      connectionPassword)
      hiveEntryOpt = Option(hiveEntry)
    }

    val tags       = mutable.ListBuffer[TagConfigEntry]()
    val tagConfigs = getConfigsOrNone(config, "tags")
    if (tagConfigs.isDefined) {
      for (tagConfig <- tagConfigs.get.asScala) {
        if (!tagConfig.hasPath("name") ||
            !tagConfig.hasPath("type.source") ||
            !tagConfig.hasPath("type.sink")) {
          LOG.error("The `name` and `type` must be specified")
          break()
        }

        val tagName = tagConfig.getString("name")
        val fields  = tagConfig.getStringList("fields").asScala.toList
        val nebulaFields = if (tagConfig.hasPath("nebula.fields")) {
          tagConfig.getStringList("nebula.fields").asScala.toList
        } else {
          fields
        }

        // You can specified the vertex field name via the com.vesoft.exchange.common.config item `vertex`
        // If you want to qualified the key policy, you can wrap them into a block.
        var prefix: String = null
        val vertexField = if (tagConfig.hasPath("vertex.field")) {
          prefix = getStringOrNull(tagConfig, "vertex.prefix")
          tagConfig.getString("vertex.field")
        } else {
          tagConfig.getString("vertex")
        }

        val policyOpt = if (tagConfig.hasPath("vertex.policy")) {
          val policy = tagConfig.getString("vertex.policy").toLowerCase
          Some(KeyPolicy.withName(policy))
        } else {
          None
        }

        val sourceCategory = toSourceCategory(tagConfig.getString("type.source"))
        val sourceConfig =
          dataSourceConfig(sourceCategory, tagConfig, nebulaConfig, variable, paths)
        LOG.info(s"Source Config ${sourceConfig}")

        val sinkCategory = toSinkCategory(tagConfig.getString("type.sink"))
        val sinkConfig   = dataSinkConfig(sinkCategory, nebulaConfig)
        LOG.info(s"Sink Config ${sourceConfig}")

        // val writeMode = toWriteModeCategory(tagConfig.getString("writeMode"))
        val writeModeStr = getOrElse(tagConfig, "writeMode", DEFAULT_WRITE_MODE)
        val writeMode    = toWriteModeCategory(writeModeStr)
        val batch        = getOrElse(tagConfig, "batch", DEFAULT_BATCH)
        val checkPointPath =
          if (tagConfig.hasPath("check_point_path")) Some(tagConfig.getString("check_point_path"))
          else DEFAULT_CHECK_POINT_PATH

        val localPath  = getOptOrElse(tagConfig, "local.path")
        val remotePath = getOptOrElse(tagConfig, "remote.path")

        val partition             = getOrElse(tagConfig, "partition", DEFAULT_PARTITION)
        val repartitionWithNebula = getOrElse(tagConfig, "repartitionWithNebula", true)
        val ignoreIndex           = getOrElse(tagConfig, "ignoreIndex", false)
        val deleteEdge            = getOrElse(tagConfig, "deleteEdge", false)

        val vertexUdf = if (tagConfig.hasPath("vertex.udf")) {
          val sep                = tagConfig.getString("vertex.udf.separator")
          val cols: List[String] = tagConfig.getStringList("vertex.udf.oldColNames").toList
          val newCol             = tagConfig.getString("vertex.udf.newColName")
          Some(UdfConfigEntry(sep, cols, newCol))
        } else None

        val filterConfig = if(tagConfig.hasPath("filter")) {
          Some(FilterConfigEntry(tagConfig.getString("filter")))
        } else None

        LOG.info(s"name ${tagName}  batch ${batch}")
        val entry = TagConfigEntry(
          tagName,
          sourceConfig,
          sinkConfig,
          fields,
          nebulaFields,
          writeMode,
          vertexField,
          policyOpt,
          prefix,
          batch,
          partition,
          checkPointPath,
          repartitionWithNebula,
          enableTagless,
          ignoreIndex,
          deleteEdge,
          vertexUdf,
          filterConfig
        )
        LOG.info(s"Tag Config: ${entry}")
        tags += entry
      }
    }

    val edges       = mutable.ListBuffer[EdgeConfigEntry]()
    val edgeConfigs = getConfigsOrNone(config, "edges")
    if (edgeConfigs.isDefined) {
      for (edgeConfig <- edgeConfigs.get.asScala) {
        if (!edgeConfig.hasPath("name") ||
            !edgeConfig.hasPath("type.source") ||
            !edgeConfig.hasPath("type.sink")) {
          LOG.error("The `name` and `type`must be specified")
          break()
        }

        val edgeName = edgeConfig.getString("name")
        val fields   = edgeConfig.getStringList("fields").asScala.toList
        val nebulaFields = if (edgeConfig.hasPath("nebula.fields")) {
          edgeConfig.getStringList("nebula.fields").asScala.toList
        } else {
          fields
        }

        val isGeo = !edgeConfig.hasPath("source") &&
          edgeConfig.hasPath("latitude") &&
          edgeConfig.hasPath("longitude")

        val sourceCategory = toSourceCategory(edgeConfig.getString("type.source"))
        val sourceConfig =
          dataSourceConfig(sourceCategory, edgeConfig, nebulaConfig, variable, paths)
        LOG.info(s"Source Config ${sourceConfig}")

        val sinkCategory = toSinkCategory(edgeConfig.getString("type.sink"))
        val sinkConfig   = dataSinkConfig(sinkCategory, nebulaConfig)
        LOG.info(s"Sink Config ${sourceConfig}")

        var sourcePrefix: String = null
        val sourceField = if (!isGeo) {
          if (edgeConfig.hasPath("source.field")) {
            sourcePrefix = getStringOrNull(edgeConfig, "source.prefix")
            edgeConfig.getString("source.field")
          } else {
            edgeConfig.getString("source")
          }
        } else {
          throw new IllegalArgumentException("Source must be specified")
        }

        val sourcePolicy = if (!isGeo) {
          if (edgeConfig.hasPath("source.policy")) {
            val policy = edgeConfig.getString("source.policy").toLowerCase
            Some(KeyPolicy.withName(policy))
          } else {
            None
          }
        } else {
          None
        }
        var targetPrefix: String = null
        val targetField: String = if (edgeConfig.hasPath("target.field")) {
          targetPrefix = getStringOrNull(edgeConfig, "target.prefix")
          edgeConfig.getString("target.field")
        } else {
          edgeConfig.getString("target")
        }

        val targetPolicy = if (edgeConfig.hasPath("target.policy")) {
          val policy = edgeConfig.getString("target.policy").toLowerCase
          Some(KeyPolicy.withName(policy))
        } else {
          None
        }

        val ranking = if (edgeConfig.hasPath("ranking")) {
          Some(edgeConfig.getString("ranking"))
        } else {
          None
        }

        val latitude = if (isGeo) {
          Some(edgeConfig.getString("latitude"))
        } else {
          None
        }

        val longitude = if (isGeo) {
          Some(edgeConfig.getString("longitude"))
        } else {
          None
        }

        val writeModeStr = getOrElse(edgeConfig, "writeMode", DEFAULT_WRITE_MODE)
        val writeMode    = toWriteModeCategory(writeModeStr)
        val batch        = getOrElse(edgeConfig, "batch", DEFAULT_BATCH)
        val checkPointPath =
          if (edgeConfig.hasPath("check_point_path")) Some(edgeConfig.getString("check_point_path"))
          else DEFAULT_CHECK_POINT_PATH

        val partition = getOrElse(edgeConfig, "partition", DEFAULT_PARTITION)

        val localPath  = getOptOrElse(edgeConfig, "path.local")
        val remotePath = getOptOrElse(edgeConfig, "path.remote")

        val repartitionWithNebula = getOrElse(edgeConfig, "repartitionWithNebula", false)
        val ignoreIndex           = getOrElse(edgeConfig, "ignoreIndex", false)

        val srcUdf = if (edgeConfig.hasPath("source.udf")) {
          val sep                = edgeConfig.getString("source.udf.separator")
          val cols: List[String] = edgeConfig.getStringList("source.udf.oldColNames").toList
          val newCol             = edgeConfig.getString("source.udf.newColName")
          Some(UdfConfigEntry(sep, cols, newCol))
        } else None

        val dstUdf = if (edgeConfig.hasPath("target.udf")) {
          val sep                = edgeConfig.getString("target.udf.separator")
          val cols: List[String] = edgeConfig.getStringList("target.udf.oldColNames").toList
          val newCol             = edgeConfig.getString("target.udf.newColName")
          Some(UdfConfigEntry(sep, cols, newCol))
        } else None


        val filterConfig = if (edgeConfig.hasPath("filter")) {
          Some(FilterConfigEntry(edgeConfig.getString("filter")))
        } else None

        val entry = EdgeConfigEntry(
          edgeName,
          sourceConfig,
          sinkConfig,
          fields,
          nebulaFields,
          writeMode,
          sourceField,
          sourcePolicy,
          sourcePrefix,
          ranking,
          targetField,
          targetPolicy,
          targetPrefix,
          isGeo,
          latitude,
          longitude,
          batch,
          partition,
          checkPointPath,
          repartitionWithNebula,
          ignoreIndex,
          srcUdf,
          dstUdf,
          filterConfig
        )
        LOG.info(s"Edge Config: ${entry}")
        edges += entry
      }
    }

    Configs(databaseEntry,
            userEntry,
            connectionEntry,
            executionEntry,
            errorEntry,
            rateEntry,
            sslEntry,
            sparkEntry,
            tags.toList,
            edges.toList,
            hiveEntryOpt)
  }

  /**
    * Use to category name to category value mapping.
    *
    * @param category name
    * @return
    */
  private[this] def toSourceCategory(category: String): SourceCategory.Value = {
    category.trim.toUpperCase match {
      case "PARQUET"    => SourceCategory.PARQUET
      case "ORC"        => SourceCategory.ORC
      case "JSON"       => SourceCategory.JSON
      case "CSV"        => SourceCategory.CSV
      case "HIVE"       => SourceCategory.HIVE
      case "NEO4J"      => SourceCategory.NEO4J
      case "KAFKA"      => SourceCategory.KAFKA
      case "MYSQL"      => SourceCategory.MYSQL
      case "PULSAR"     => SourceCategory.PULSAR
      case "HBASE"      => SourceCategory.HBASE
      case "MAXCOMPUTE" => SourceCategory.MAXCOMPUTE
      case "CLICKHOUSE" => SourceCategory.CLICKHOUSE
      case "POSTGRESQL" => SourceCategory.POSTGRESQL
      case "ORACLE"     => SourceCategory.ORACLE
      case "JDBC"       => SourceCategory.JDBC
      case _            => SourceCategory.CUSTOM
    }
  }

  /**
    * Use to sink name to sink value mapping.
    *
    * @param category name
    * @return
    */
  private[this] def toSinkCategory(category: String): SinkCategory.Value = {
    category.trim.toUpperCase match {
      case "CLIENT" => SinkCategory.CLIENT
      case "SST"    => SinkCategory.SST
      case _        => throw new IllegalArgumentException(s"${category} not support")
    }
  }

  /**
    * Use to get write mode according to category of writeMode.
    *
    * @param category
    * @return
    */
  private[this] def toWriteModeCategory(category: String): WriteMode.Mode = {
    category.trim.toUpperCase match {
      case "INSERT" => WriteMode.INSERT
      case "UPDATE" => WriteMode.UPDATE
      case "DELETE" => WriteMode.DELETE
      case _        => throw new IllegalArgumentException(s"${category} not support")
    }
  }

  /**
    * Use to generate data source com.vesoft.exchange.common.config according to category of source.
    *
    * @param category
    * @param config
    * @return
    */
  private[this] def dataSourceConfig(category: SourceCategory.Value,
                                     config: Config,
                                     nebulaConfig: Config,
                                     variable: Boolean,
                                     paths: Map[String, String]): DataSourceConfigEntry = {
    category match {
      case SourceCategory.PARQUET =>
        if (variable)
          FileBaseSourceConfigEntry(SourceCategory.PARQUET, paths(config.getString("path")))
        else FileBaseSourceConfigEntry(SourceCategory.PARQUET, config.getString("path"))
      case SourceCategory.ORC =>
        if (variable) FileBaseSourceConfigEntry(SourceCategory.ORC, paths(config.getString("path")))
        else FileBaseSourceConfigEntry(SourceCategory.ORC, config.getString("path"))
      case SourceCategory.JSON =>
        if (variable)
          FileBaseSourceConfigEntry(SourceCategory.JSON, paths(config.getString("path")))
        else FileBaseSourceConfigEntry(SourceCategory.JSON, config.getString("path"))
      case SourceCategory.CSV =>
        val separator =
          if (config.hasPath("separator"))
            config.getString("separator")
          else ","
        val header =
          if (config.hasPath("header"))
            config.getBoolean("header")
          else
            false
        if (variable)
          FileBaseSourceConfigEntry(SourceCategory.CSV,
                                    paths(config.getString("path")),
                                    Some(separator),
                                    Some(header))
        else
          FileBaseSourceConfigEntry(SourceCategory.CSV,
                                    config.getString("path"),
                                    Some(separator),
                                    Some(header))
      case SourceCategory.HIVE =>
        HiveSourceConfigEntry(SourceCategory.HIVE, config.getString("exec"))
      case SourceCategory.NEO4J =>
        val name = config.getString("name")
        val checkPointPath =
          if (config.hasPath("check_point_path")) Some(config.getString("check_point_path"))
          else DEFAULT_CHECK_POINT_PATH
        val encryption =
          if (config.hasPath("encryption")) config.getBoolean("encryption") else false
        val parallel =
          if (config.hasPath("partition")) config.getInt("partition") else DEFAULT_PARALLEL
        if (parallel <= 0)
          throw new IllegalArgumentException(s"Can't set neo4j ${name} partition<=0.")
        val database = if (config.hasPath("database")) Some(config.getString("database")) else None
        Neo4JSourceConfigEntry(
          SourceCategory.NEO4J,
          config.getString("exec"),
          name,
          config.getString("server"),
          config.getString("user"),
          config.getString("password"),
          database,
          encryption,
          parallel,
          checkPointPath
        )
      case SourceCategory.JANUS_GRAPH =>
        JanusGraphSourceConfigEntry(SourceCategory.JANUS_GRAPH, "", false) // TODO
      case SourceCategory.MYSQL =>
        MySQLSourceConfigEntry(
          SourceCategory.MYSQL,
          config.getString("host"),
          config.getInt("port"),
          config.getString("database"),
          getStringOrNull(config, "table"),
          config.getString("user"),
          config.getString("password"),
          getStringOrNull(config, "sentence")
        )
      case SourceCategory.POSTGRESQL =>
        PostgreSQLSourceConfigEntry(
          SourceCategory.POSTGRESQL,
          config.getString("host"),
          config.getInt("port"),
          config.getString("database"),
          getStringOrNull(config, "table"),
          config.getString("user"),
          config.getString("password"),
          getStringOrNull(config, "sentence")
        )
      case SourceCategory.ORACLE =>
        OracleConfigEntry(
          SourceCategory.ORACLE,
          config.getString("url"),
          config.getString("driver"),
          config.getString("user"),
          config.getString("password"),
          getStringOrNull(config, "table"),
          getStringOrNull(config, "sentence")
        )
      case SourceCategory.JDBC =>
        val partitionColumn =
          if (config.hasPath("partitionColumn"))
            Some(config.getString("partitionColumn"))
          else None

        val lowerBound =
          if (config.hasPath("lowerBound"))
            Some(config.getLong("lowerBound"))
          else None

        val upperBound =
          if (config.hasPath("upperBound"))
            Some(config.getLong("upperBound"))
          else None

        val numPartitions =
          if (config.hasPath("numPartitions"))
            Some(config.getLong("numPartitions"))
          else None

        val fetchSize =
          if (config.hasPath("fetchSize"))
            Some(config.getLong("fetchSize"))
          else None

        JdbcConfigEntry(
          SourceCategory.JDBC,
          config.getString("url"),
          config.getString("driver"),
          config.getString("user"),
          config.getString("password"),
          getStringOrNull(config, "table"),
          partitionColumn,
          lowerBound,
          upperBound,
          numPartitions,
          fetchSize,
          getStringOrNull(config, "sentence")
        )
      case SourceCategory.KAFKA =>
        val intervalSeconds =
          if (config.hasPath("interval.seconds")) config.getInt("interval.seconds")
          else DEFAULT_STREAM_INTERVAL
        val startingOffsets =
          if (config.hasPath("startingOffsets")) config.getString("startingOffsets")
          else DEFAULT_KAFKA_STARTINGOFFSETS
        val maxOffsetsPerTrigger =
          if (config.hasPath("maxOffsetsPerTrigger")) Some(config.getLong("maxOffsetsPerTrigger"))
          else None

        val securityProtocol =
          if (config.hasPath("securityProtocol")) Some(config.getString("securityProtocol"))
          else None
        val mechanism =
          if (config.hasPath("mechanism")) Some(config.getString("mechanism")) else None
        val kerberos = if (config.hasPath("kerberos")) config.getBoolean("kerberos") else false
        val kerberosServiceName =
          if (config.hasPath("kerberosServiceName")) config.getString("kerberosServiceName")
          else null

        KafkaSourceConfigEntry(
          SourceCategory.KAFKA,
          intervalSeconds,
          config.getString("service"),
          config.getString("topic"),
          startingOffsets,
          maxOffsetsPerTrigger,
          securityProtocol,
          mechanism,
          kerberos,
          kerberosServiceName
        )
      case SourceCategory.PULSAR =>
        val options =
          config.getObject("options").unwrapped.asScala.map(x => x._1 -> x._2.toString).toMap
        val intervalSeconds =
          if (config.hasPath("interval.seconds")) config.getInt("interval.seconds")
          else DEFAULT_STREAM_INTERVAL
        PulsarSourceConfigEntry(SourceCategory.PULSAR,
                                intervalSeconds,
                                config.getString("service"),
                                config.getString("admin"),
                                options)
      case SourceCategory.HBASE =>
        val fields: ListBuffer[String] = new ListBuffer[String]
        fields.append(config.getStringList("fields").asScala: _*)

        if (config.hasPath("vertex.field")) {
          fields.append(config.getString("vertex.field"))
        }
        if (config.hasPath("source.field")) {
          fields.append(config.getString("source.field"))
        }
        if (config.hasPath("target.field")) {
          fields.append(config.getString("target.field"))
        }

        HBaseSourceConfigEntry(SourceCategory.HBASE,
                               config.getString("host"),
                               config.getString("port"),
                               config.getString("table"),
                               config.getString("columnFamily"),
                               fields.toSet.toList)
      case SourceCategory.MAXCOMPUTE => {
        val table         = config.getString("table")
        val partitionSpec = getStringOrNull(config, "partitionSpec")
        val numPartitions = getOrElse(config, "numPartitions", 1).toString
        val sentence      = getStringOrNull(config, "sentence")

        MaxComputeConfigEntry(
          SourceCategory.MAXCOMPUTE,
          config.getString("odpsUrl"),
          config.getString("tunnelUrl"),
          table,
          config.getString("project"),
          config.getString("accessKeyId"),
          config.getString("accessKeySecret"),
          partitionSpec,
          numPartitions,
          sentence
        )
      }
      case SourceCategory.CLICKHOUSE => {
        val partition: String = getOrElse(config, "numPartition", "1")
        ClickHouseConfigEntry(
          SourceCategory.CLICKHOUSE,
          config.getString("url"),
          config.getString("user"),
          config.getString("password"),
          partition,
          getStringOrNull(config, "table"),
          getStringOrNull(config, "sentence")
        )
      }
      case SourceCategory.CUSTOM => {
        //config parse may use the CustomSourceConfigEntry to pass raw  config when the config option is not supported
        DataSourcePlugin.HandleConfig(category, config, nebulaConfig, variable, paths)
      }
      case _ =>
        throw new IllegalArgumentException("Unsupported data source")
    }
  }

  private[this] def dataSinkConfig(category: SinkCategory.Value,
                                   nebulaConfig: Config): DataSinkConfigEntry = {
    category match {
      case SinkCategory.CLIENT =>
        NebulaSinkConfigEntry(SinkCategory.CLIENT,
                              nebulaConfig.getStringList("address.graph").asScala.toList)
      case SinkCategory.SST => {
        val fsNameNode = {
          if (nebulaConfig.hasPath("path.hdfs.namenode"))
            Option(nebulaConfig.getString("path.hdfs.namenode"))
          else {
            LOG.warn("Import mode is SST, hdfs namenode is not set.")
            Option.empty
          }
        }
        FileBaseSinkConfigEntry(SinkCategory.SST,
                                nebulaConfig.getString("path.local"),
                                nebulaConfig.getString("path.remote"),
                                fsNameNode)
      }
      case _ =>
        throw new IllegalArgumentException("Unsupported data sink")
    }
  }

  /**
    * Get the com.vesoft.exchange.common.config list by the path.
    *
    * @param config The com.vesoft.exchange.common.config.
    * @param path   The path of the com.vesoft.exchange.common.config.
    * @return
    */
  private[this] def getConfigsOrNone(config: Config,
                                     path: String): Option[java.util.List[_ <: Config]] = {
    if (config.hasPath(path)) {
      Some(config.getConfigList(path))
    } else {
      None
    }
  }

  /**
    * Get the com.vesoft.exchange.common.config by the path.
    *
    * @param config
    * @param path
    * @return
    */
  def getConfigOrNone(config: Config, path: String): Option[Config] = {
    if (config.hasPath(path)) {
      Some(config.getConfig(path))
    } else {
      None
    }
  }

  /**
    * Get the value from com.vesoft.exchange.common.config by the path. If the path not exist, return the default value.
    *
    * @param config       The com.vesoft.exchange.common.config.
    * @param path         The path of the com.vesoft.exchange.common.config.
    * @param defaultValue The default value for the path.
    * @return
    */
  private[this] def getOrElse[T](config: Config, path: String, defaultValue: T): T = {
    if (config.hasPath(path)) {
      config.getAnyRef(path).asInstanceOf[T]
    } else {
      defaultValue
    }
  }

  /**
    * Get the String value from config
    *
    * @param config config item
    * @path the path of config
    * @return String
    */
  private[this] def getStringOrNull(config: Config, path: String): String = {
    if (config.hasPath(path)) {
      config.getString(path)
    } else {
      null
    }
  }

  private[this] def getOptOrElse(config: Config, path: String): Option[String] = {
    if (config.hasPath(path)) {
      Some(config.getString(path))
    } else {
      None
    }
  }

  /**
    * Get the value from com.vesoft.exchange.common.config by the path which is optional.
    * If the path not exist, return the default value.
    *
    * @param config
    * @param path
    * @param defaultValue
    * @tparam T
    * @return
    */
  private[this] def getOrElse[T](config: Option[Config], path: String, defaultValue: T): T = {
    if (config.isDefined && config.get.hasPath(path)) {
      config.get.getAnyRef(path).asInstanceOf[T]
    } else {
      defaultValue
    }
  }

  /**
    * Use to parse command line arguments.
    *
    * @param args
    * @param programName
    * @return Argument
    */
  def parser(args: Array[String], programName: String): Option[Argument] = {
    val parser = new scopt.OptionParser[Argument](programName) {
      head(programName, "2.0.0")

      opt[String]('c', "config")
        .required()
        .valueName("fileName")
        .action((x, c) => c.copy(config = x))
        .text("com.vesoft.exchange.common.config fileName")

      opt[Unit]('h', "hive")
        .action((_, c) => c.copy(hive = true))
        .text("hive supported")

      opt[Unit]('d', "directly")
        .action((_, c) => c.copy(directly = true))
        .text("directly mode")

      opt[Unit]('D', "dry")
        .action((_, c) => c.copy(dry = true))
        .text("dry run")

      opt[String]('r', "reload")
        .valueName("<path>")
        .action((x, c) => c.copy(reload = x))
        .text("reload path")

      opt[Unit]('v', "variable")
        .action((_, c) => c.copy(variable = true))
        .text("enable file param")

      opt[String]('p', "param")
        .valueName("<param>")
        .action((x, c) => c.copy(param = x))
        .text("file param path")

    }
    parser.parse(args, Argument())
  }
}
