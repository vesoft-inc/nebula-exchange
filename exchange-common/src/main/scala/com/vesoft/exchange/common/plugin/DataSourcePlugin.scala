package com.vesoft.exchange.common.plugin

import com.typesafe.config.Config
import com.vesoft.exchange.common.config.{DataSourceConfigEntry, SourceCategory}

import org.apache.log4j.Logger
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable
import scala.reflect.runtime.{universe => ru}

/**
 * DataSource plugin definition and management for NebulaExchange
 */

abstract class DataSourcePlugin {

  //categoryT info
  def categoryType:SourceCategory.Value = SourceCategory.CUSTOM
  def categoryName:String

  //configEntity parser
  def dataSourceConfigParser(category: SourceCategory.Value,
                             config: Config,
                             nebulaConfig: Config,
                             variable: Boolean,
                             paths: Map[String, String]): DataSourceConfigEntry

  //load data
  def readData(session:SparkSession,config:DataSourceConfigEntry,fields:List[String]):Option[DataFrame]

}

abstract class DataSourcePluginCompanion {
  private val LOG = Logger.getLogger(this.getClass)
  private var _pluginInstance:Option[DataSourcePlugin] = None

  /*-------------------instance manager-----------------------------*/

  def createPlugin(name: String):DataSourcePlugin

  final def initPlugin(name: String):Unit = {
    if(_pluginInstance.isEmpty){
      _pluginInstance = Some(createPlugin(name))
    }
  }

  final def getPlugin(name:String):Option[DataSourcePlugin] = {
    _pluginInstance
  }

  def clearPlugin(): Unit = {
    LOG.info(s">>>>> Clearing plugin instance")
    _pluginInstance = None
  }

}



object DataSourcePlugin{
  private[this] val LOG = Logger.getLogger(this.getClass)
  /*-------------------Companion Manager-----------------------------*/
  private[this] val nameToCompanion = mutable.Map[String,DataSourcePluginCompanion]()
  private[this] val elementToCompanion = mutable.Map[String,DataSourcePluginCompanion]()

  //Companion Reflection
  private[this] def lookupCompanion(name: String):DataSourcePluginCompanion = {
    //use the same cl for the class and companion,here use default app classLoader in JDK8
    val cl = getClass.getClassLoader
    //TODO can be removed ?
    Class.forName(name,false,cl)
    val mirror = ru.runtimeMirror(cl)
    val companionSymbol = mirror.staticModule(name)
    val companion = mirror.reflectModule(companionSymbol).instance.asInstanceOf[DataSourcePluginCompanion]
    companion
  }

  /*-----------------HandleConfig and create DataSource-------------*/
  def HandleConfig(category: SourceCategory.Value,
                   config: Config,
                   nebulaConfig: Config,
                   variable: Boolean,
                   paths: Map[String, String]):DataSourceConfigEntry = {
    val name = config.getString("type.source")
    val elementName = config.getString("name")
    nameToCompanion.get(name) match {
      case Some(companion) => {
        //already init
        LOG.info(s">>>>> parser config with ${name} that are already loaded")
        elementToCompanion += elementName -> companion
        companion.getPlugin(name).get.dataSourceConfigParser(category, config, nebulaConfig, variable, paths)
      }
      case None => {
        //first init
        val companion = lookupCompanion(name)
        nameToCompanion += name -> companion
        elementToCompanion += elementName -> companion
        companion.initPlugin(name)
        LOG.info(s">>>>> parser config with ${name} that are first loaded")
        companion.getPlugin(name).get.dataSourceConfigParser(category, config, nebulaConfig, variable, paths)
      }
    }
  }


  def ReadData(session:SparkSession,config:DataSourceConfigEntry,fields:List[String],element:String):Option[DataFrame] = {
    elementToCompanion.get(element) match {
      case Some(companion) => {
        companion.getPlugin(element).get.readData(session,config,fields)
      }
      case None => {
        LOG.error(s">>>>> elementType $element not found")
        None
      }
    }
  }
}
