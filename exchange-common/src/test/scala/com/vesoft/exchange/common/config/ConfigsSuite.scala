/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package scala.com.vesoft.nebula.exchange.config

import java.io.File

import com.vesoft.exchange.Argument
import com.vesoft.exchange.common.KeyPolicy
import com.vesoft.exchange.common.config.{
  CaSignParam,
  Configs,
  DataBaseConfigEntry,
  FileBaseSourceConfigEntry,
  FileDataSourceConfigEntry,
  HBaseSourceConfigEntry,
  HiveSourceConfigEntry,
  MySQLSourceConfigEntry,
  Neo4JSourceConfigEntry,
  PostgreSQLSourceConfigEntry,
  SelfSignParam,
  SinkCategory,
  SourceCategory,
  SslConfigEntry,
  SslType
}
import com.vesoft.nebula.client.graph.data.CASignedSSLParam
import org.apache.log4j.Logger
import org.junit.Test
import org.scalatest.Assertions.assertThrows

class ConfigsSuite {
  private[this] val LOG = Logger.getLogger(this.getClass)

  @Test
  def configsSuite(): Unit = {
    val args    = List("-c", "src/test/resources/application.conf", "-h", "-d")
    val options = Configs.parser(args.toArray, "test")
    val c: Argument = options match {
      case Some(config) => config
      case _ =>
        assert(false)
        sys.exit(-1)
    }
    assert(c.hive)
    assert(c.directly)

    val configs             = Configs.parse(c.config)
    val dataBaseConfigEntry = configs.databaseConfig
    val userConfig          = configs.userConfig
    val connectionConfig    = configs.connectionConfig
    val executionConfig     = configs.executionConfig
    val errorConfig         = configs.errorConfig
    val rateConfig          = configs.rateConfig
    val tagsConfig          = configs.tagsConfig
    val edgesConfig         = configs.edgesConfig
    val hiveConfigEntry     = configs.hiveConfigEntry

    assert(dataBaseConfigEntry.graphAddress.size == 3)
    assert(dataBaseConfigEntry.metaAddresses.size == 3)
    assert(dataBaseConfigEntry.space.equals("test"))

    assert(userConfig.user.equals("root"))
    assert(userConfig.password.equals("nebula"))

    assert(connectionConfig.retry == 3)
    assert(connectionConfig.timeout == 3000)

    assert(executionConfig.retry == 3)
    assert(executionConfig.interval == 3000)
    assert(executionConfig.timeout == Integer.MAX_VALUE)

    assert(errorConfig.errorMaxSize == 32)
    assert(errorConfig.errorPath.equals("/tmp/errors"))

    assert(rateConfig.limit == 1024)
    assert(rateConfig.timeout == 1000)

    if (hiveConfigEntry.get != null) {
      assert(hiveConfigEntry.get.connectionDriverName.equals("com.mysql.jdbc.Driver"))
      assert(hiveConfigEntry.get.connectionPassWord.equals("password"))
      assert(
        hiveConfigEntry.get.connectionURL
          .equals("jdbc:mysql://your_ip:3306/hive_spark?characterEncoding=UTF-8"))
      assert(hiveConfigEntry.get.connectionUserName.equals("user"))
      assert(
        hiveConfigEntry.get.warehouse
          .equals("hdfs://NAMENODE_IP:9000/apps/svr/hive-xxx/warehouse/"))
    }

    for (tagConfig <- tagsConfig) {
      val source = tagConfig.dataSourceConfigEntry
      val sink   = tagConfig.dataSinkConfigEntry
      assert(sink.category == SinkCategory.CLIENT || sink.category == SinkCategory.SST)

      val label     = tagConfig.name
      val batch     = tagConfig.batch
      val partition = tagConfig.partition

      val vertexField = tagConfig.vertexField
      val policy      = tagConfig.vertexPolicy
      if (policy.isDefined) {
        assert(policy.get == KeyPolicy.UUID || policy.get == KeyPolicy.HASH)
      }

      val nebulaFields = tagConfig.nebulaFields
      val fields       = tagConfig.fields
      assert(nebulaFields.size == fields.size)

      source.category match {
        case SourceCategory.CSV => {
          val csv = tagConfig.dataSourceConfigEntry.asInstanceOf[FileBaseSourceConfigEntry]
          assert(label.equals("tag1"))
          assert(csv.header.get)
          assert(csv.separator.get.equals("|"))
          assert(csv.path.equals("path1"))
        }
        case SourceCategory.JSON => {
          val json = tagConfig.dataSourceConfigEntry.asInstanceOf[FileDataSourceConfigEntry]
          assert(label.equals("tag2"))
          assert(json.path.equals("path3"))
        }
        case SourceCategory.PARQUET => {
          val parquet = tagConfig.dataSourceConfigEntry.asInstanceOf[FileDataSourceConfigEntry]
          assert(label.equals("tag0"))
          assert(parquet.path.equals("path0"))
        }
        case SourceCategory.HIVE => {
          val hive = tagConfig.dataSourceConfigEntry.asInstanceOf[HiveSourceConfigEntry]
          assert(label.equals("tag3"))
          assert(
            hive.sentence.equals(
              "select hive-field0, hive-field1, hive-field2 from database.table"))
        }
        case SourceCategory.NEO4J => {
          val neo4j = tagConfig.dataSourceConfigEntry.asInstanceOf[Neo4JSourceConfigEntry]
          assert(label.equals("tag4"))
          assert(!neo4j.database.isDefined)
          assert(neo4j.server.equals("bolt://127.0.0.1:7687"))
          assert(neo4j.user.equals("neo4j"))
          assert(neo4j.password.equals("neo4j"))
          assert(neo4j.sentence.equals(
            "match (n:label) return n.neo4j-field-0 as neo4j-field-0, n.neo4j-field-1 as neo4j-field-1 order by (n.neo4j-field-0)"))
        }
        case SourceCategory.HBASE => {
          val hbase = tagConfig.dataSourceConfigEntry.asInstanceOf[HBaseSourceConfigEntry]
          assert(label.equals("tag5"))
          assert(hbase.columnFamily.equals("hbase-table-cloumnfamily"))
          assert(hbase.host.equals("127.0.0.1"))
          assert(hbase.port.equals("2181"))
        }
        case SourceCategory.MYSQL => {
          val mysql = tagConfig.dataSourceConfigEntry.asInstanceOf[MySQLSourceConfigEntry]
          assert(label.equals("tag8"))
          assert(mysql.database.equals("database"))
          assert(mysql.host.equals("127.0.0.1"))
          assert(mysql.port == 3306)
          assert(mysql.user.equals("root"))
          assert(mysql.password.equals("nebula"))
          assert(mysql.database.equals("database"))
          assert(mysql.table.equals("table"))
        }
        case SourceCategory.POSTGRESQL => {
          val postgresql = tagConfig.dataSourceConfigEntry.asInstanceOf[PostgreSQLSourceConfigEntry]
          assert(label.equals("tag9"))
          assert(postgresql.database.equals("database"))
          assert(postgresql.host.equals("127.0.0.1"))
          assert(postgresql.port == 5432)
          assert(postgresql.user.equals("root"))
          assert(postgresql.password.equals("nebula"))
          assert(postgresql.table.equals("table"))
        }
        case _ => {}
      }
    }

    for (edgeConfig <- edgesConfig) {
      val source = edgeConfig.dataSourceConfigEntry
      val sink   = edgeConfig.dataSinkConfigEntry
      assert(sink.category == SinkCategory.CLIENT || sink.category == SinkCategory.SST)

      val label     = edgeConfig.name
      val batch     = edgeConfig.batch
      val partition = edgeConfig.partition

      val sourceField  = edgeConfig.sourceField
      val targetField  = edgeConfig.targetField
      val sourcePolicy = edgeConfig.sourcePolicy
      val targetPolicy = edgeConfig.targetPolicy
      if (sourcePolicy.isDefined) {
        assert(sourcePolicy.get == KeyPolicy.UUID || sourcePolicy.get == KeyPolicy.HASH)
      }
      if (targetPolicy.isDefined) {
        assert(targetPolicy.get == KeyPolicy.UUID || targetPolicy.get == KeyPolicy.HASH)
      }

      val nebulaFields = edgeConfig.nebulaFields
      val fields       = edgeConfig.fields
      assert(nebulaFields.size == fields.size)

      source.category match {
        case SourceCategory.CSV => {
          val csv = edgeConfig.dataSourceConfigEntry.asInstanceOf[FileBaseSourceConfigEntry]
          assert(label.equals("edge1"))
          assert(csv.header.get)
          assert(csv.separator.get.equals(","))
          assert(csv.path.equals("path1"))
          assert(batch == 256)
          assert(partition == 32)
        }
        case SourceCategory.JSON => {
          val json = edgeConfig.dataSourceConfigEntry.asInstanceOf[FileDataSourceConfigEntry]
          assert(label.equals("edge2"))
          assert(json.path.equals("path2"))
          assert(batch == 256)
          assert(partition == 32)
        }
        case SourceCategory.PARQUET => {
          val parquet = edgeConfig.dataSourceConfigEntry.asInstanceOf[FileDataSourceConfigEntry]
          assert(label.equals("edge0"))
          assert(parquet.path.equals("path0"))
          assert(batch == 256)
          assert(partition == 32)
        }
        case SourceCategory.HIVE => {
          val hive = edgeConfig.dataSourceConfigEntry.asInstanceOf[HiveSourceConfigEntry]
          assert(label.equals("edge3"))
          assert(
            hive.sentence.equals(
              "select hive-field0, hive-field1, hive-field2 from database.table"))
          assert(batch == 256)
          assert(partition == 32)
        }
        case SourceCategory.NEO4J => {
          val neo4j = edgeConfig.dataSourceConfigEntry.asInstanceOf[Neo4JSourceConfigEntry]
          assert(label.equals("edge4"))
          assert(!neo4j.database.isDefined)
          assert(neo4j.server.equals("bolt://127.0.0.1:7687"))
          assert(neo4j.user.equals("neo4j"))
          assert(neo4j.password.equals("neo4j"))
          assert(neo4j.sentence.equals(
            "match (a:vertex_label)-[r:edge_label]->(b:vertex_label) return a.neo4j-source-field, b.neo4j-target-field, r.neo4j-field-0 as neo4j-field-0, r.neo4j-field-1 as neo4j-field-1 order by id(r)"))
          assert(batch == 1000)
          assert(partition == 10)
        }
        case SourceCategory.HBASE => {
          val hbase = edgeConfig.dataSourceConfigEntry.asInstanceOf[HBaseSourceConfigEntry]
          assert(label.equals("edge5"))
          assert(hbase.columnFamily.equals("hbase-table-cloumnfamily"))
          assert(hbase.host.equals("127.0.0.1"))
          assert(hbase.port.equals("2181"))
          assert(batch == 1000)
          assert(partition == 10)
        }
        case _ => {}
      }
    }
  }

  /**
    * correct com.vesoft.exchange.common.config
    */
  @Test
  def dataBaseConfigSuite(): Unit = {
    val graphAddress = List("127.0.0.1:9669", "127.0.0.1:9670")
    val metaAddress  = List("127.0.0.1:9559", "127.0.0.1:9560")
    val space        = "test"
    DataBaseConfigEntry(graphAddress, space, metaAddress)
  }

  /**
    * empty space
    */
  @Test
  def dataBaseConfigEmptySpaceSuite: Unit = {
    val graphAddress = List("127.0.0.1:9669", "127.0.0.1:9670")
    val metaAddress  = List("127.0.0.1:9559", "127.0.0.1:9560")
    assertThrows[IllegalArgumentException] {
      DataBaseConfigEntry(graphAddress, "", metaAddress)
    }
  }

  /**
    * wrong graph address
    */
  @Test
  def dataBaseConfigWrongGraphSuite: Unit = {
    val wrongGraphAddress = List("127.0.0.1:9669,127.0.0.1:9670")
    val space             = "test"
    val metaAddress       = List("127.0.0.1:9559", "127.0.0.1:9560")

    assertThrows[IllegalArgumentException] {
      DataBaseConfigEntry(wrongGraphAddress, space, metaAddress)
    }
  }

  /**
    * wrong meta Address
    */
  @Test
  def dataBaseConfigWrongMetaSuite: Unit = {
    val graphAddress     = List("127.0.0.1:9669", "127.0.0.1:9670")
    val space            = "test"
    val wrongMetaAddress = List("127.0.0.1:9559，127.0.0.1:9560")
    assertThrows[IllegalArgumentException] {
      DataBaseConfigEntry(graphAddress, space, wrongMetaAddress)
    }
  }

  @Test
  def sslConfigSuite: Unit = {
    val enableGraph   = true
    val enableMeta    = true
    val caSignParam   = CaSignParam("caPath", "crtPath", "keyPath")
    val selfSignParam = SelfSignParam("crtPath", "keyPath", "nebula")

    SslConfigEntry(enableGraph, enableMeta, SslType.CA, caSignParam, null)
    SslConfigEntry(enableGraph, enableMeta, SslType.SELF, null, selfSignParam)

    assertThrows[IllegalArgumentException](
      SslConfigEntry(enableGraph, enableMeta, SslType.CA, null, null))
    assertThrows[IllegalArgumentException](
      SslConfigEntry(enableGraph, enableMeta, SslType.SELF, null, null))
  }
}
