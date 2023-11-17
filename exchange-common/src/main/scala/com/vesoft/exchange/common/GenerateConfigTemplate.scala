/* Copyright (c) 2023 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common

import com.vesoft.exchange.common.config.SourceCategory
import org.apache.commons.cli.{
  CommandLine,
  CommandLineParser,
  HelpFormatter,
  Option,
  Options,
  ParseException,
  PosixParser
}

object GenerateConfigTemplate {

  def main(args: Array[String]): Unit = {
    val sourceOption = new Option("s", "dataSource", true, "data source type")
    sourceOption.setRequired(true)

    val pathOption = new Option("p", "path", true, "target path to save the template config file")
    pathOption.setRequired(true)

    val options = new Options
    options.addOption(sourceOption)
    options.addOption(pathOption)

    var cli: CommandLine             = null
    val cliParser: CommandLineParser = new PosixParser()
    val helpFormatter                = new HelpFormatter
    try {
      cli = cliParser.parse(options, args)
    } catch {
      case e: ParseException =>
        helpFormatter.printHelp(">>>> options", options)
        e.printStackTrace()
        System.exit(1)
    }
    val source: String = cli.getOptionValue("s")
    val path: String   = cli.getOptionValue("p")

    getConfigTemplate(source, path)
  }

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
      case SourceCategory.JDBC | SourceCategory.MYSQL | SourceCategory.CLICKHOUSE |
          SourceCategory.MAXCOMPUTE | SourceCategory.ORC | SourceCategory.POSTGRESQL =>
        fileMigrate.saveConfig("config_template/jdbc.conf", path + "/jdbc.conf")
      case SourceCategory.NEO4J =>
        fileMigrate.saveConfig("config_template/neo4j.conf", path + "/neo4j.conf")
      case _ => throw new IllegalArgumentException(s"does not support datasource $sourceCategory")
    }
  }

}
