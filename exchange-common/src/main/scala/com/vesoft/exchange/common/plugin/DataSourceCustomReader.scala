package com.vesoft.exchange.common.plugin

import com.vesoft.exchange.common.config.DataSourceConfigEntry
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
  * Custom reader for DataSource
  */

abstract class  DataSourceCustomReader {
  def readData(session:SparkSession,config:DataSourceConfigEntry,fields:List[String]):Option[DataFrame]
}
