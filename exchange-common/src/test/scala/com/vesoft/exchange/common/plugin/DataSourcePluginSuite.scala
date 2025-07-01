package com.vesoft.exchange.common.plugin

import com.vesoft.exchange.common.config.SourceCategory
import org.apache.log4j.Logger
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.junit.{Before, Test}

import java.io.File
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.reflect.runtime.{universe => ru}


class DataSourcePluginSuite {
  private[this] val LOG = Logger.getLogger(this.getClass)
  private[this] val pluginJarPath = "../exchange-plugin-impl_spark_3.0/target/exchange-plugin-impl_spark_3.0-3.0-SNAPSHOT.jar"

  private[this] def lookupCompanion(name: String):DataSourcePluginCompanion = {
    //use the same cl for the class and companion must share the same cl
    val cl = Thread.currentThread().getContextClassLoader
    // Class.forName(name,false,cl)
    val mirror = ru.runtimeMirror(cl)
    val companionSymbol = mirror.staticModule(name)
    val companion = mirror.reflectModule(companionSymbol).instance.asInstanceOf[DataSourcePluginCompanion]
    companion
  }

  @Before
  def setUp(): Unit = {
    //junit can't find the spark --jars,so we must use URLClassLoader to load the jar
    val url = new File(pluginJarPath).toURI.toURL
    val classLoader = new URLClassLoader(Array(url), getClass.getClassLoader)
    Thread.currentThread().setContextClassLoader(classLoader)
  }
  @Test
  def externalPluginLoaderSuite():Unit = {
    val conf = new SparkConf().setJars(Array(pluginJarPath))
    val session = SparkSession.builder()
      .appName("plugin loader test")
      .master("local")
      .config(conf)
      .getOrCreate()

    //force load for the default case
    try {
      val name = "com.vesoft.nebula.exchange.plugin.fileBase.CsvDataSourcePlugin"
      //val name = "com.vesoft.nebula.exchange.plugin.fileBase.ParquetDataSourcePlugin"
      val pluginCompanion = lookupCompanion(name)
      pluginCompanion.initPlugin(name)
      val plugin = pluginCompanion.getPlugin(name).get
      assert(plugin.categoryType == SourceCategory.CUSTOM)
      assert(plugin.categoryName == "csv-custom")
      assert(plugin.getClass.getName == name)
      LOG.info(s">>>>> ${plugin.categoryName} load success!")
    } catch {
      case e: Exception => {
        LOG.error(s">>>>>${e}")
        throw e
      }
    } finally {
      session.stop()
    }
  }
}
