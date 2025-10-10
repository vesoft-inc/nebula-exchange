package com.vesoft.exchange.common.plugin

import com.typesafe.config.Config
import com.vesoft.exchange.common.config.{CustomSourceConfigEntry, DataSourceConfigEntry, SourceCategory}

/**
  *
  */
abstract class DataSourceConfigResolver {
  def getDataSourceConfigEntry(category: SourceCategory.Value,
                               config: Config,
                               nebulaConfig: Config): DataSourceConfigEntry = {
    val customConfig = config.getConfig("custom")
    val readerClazz  = customConfig.getString("reader")
    CustomSourceConfigEntry(category,readerClazz,config,nebulaConfig)
  }
}
