/* Copyright (c) 2023 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.utils

import com.vesoft.exchange.common.FileMigrate
import com.vesoft.exchange.common.config.SourceCategory

import java.io.{BufferedInputStream, BufferedOutputStream, File, FileOutputStream, InputStream}

object ConfigTemplateUtils {

  def getConfigTemplate(source: String, path: String): Unit = {
    val sourceCategory = SourceCategory.withName(source.trim.toUpperCase)

    val fileMigrate = new FileMigrate
    sourceCategory match {
      case SourceCategory.CSV =>
        fileMigrate.saveConfig("config_template/csv.conf", path + "/csv.conf")
      case SourceCategory.JSON =>
        fileMigrate.saveConfig("config_template/json.conf", path + "/json.conf")
      case SourceCategory.ORC =>
        fileMigrate.saveConfig("config_template/orc.conf", path + "/orc.conf")
      case SourceCategory.PARQUET =>
        fileMigrate.saveConfig("config_template/parquet.conf", path + "/parquet.conf")
      case SourceCategory.HIVE =>
        fileMigrate.saveConfig("config_template/hive.conf", path + "/hive.conf")
      case SourceCategory.HBASE=>
        fileMigrate.saveConfig("config_template/hbase.conf", path + "/hbase.conf")
      case SourceCategory.JDBC | SourceCategory.MYSQL | SourceCategory.CLICKHOUSE |
          SourceCategory.MAXCOMPUTE | SourceCategory.ORC | SourceCategory.POSTGRESQL =>
        fileMigrate.saveConfig("config_template/jdbc.conf", path + "/jdbc.conf")
      case SourceCategory.NEO4J =>
        fileMigrate.saveConfig("config_template/neo4j.conf", path + "/neo4j.conf")
      case SourceCategory.KAFKA | SourceCategory.PULSAR =>
        fileMigrate.saveConfig("config_template/kafka.conf", path + "/kafka.conf")
      case _ => throw new IllegalArgumentException(s"does not support datasource $sourceCategory")
    }
  }

}
