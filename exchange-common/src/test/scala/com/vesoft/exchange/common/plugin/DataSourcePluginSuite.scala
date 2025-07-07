package com.vesoft.exchange.common.plugin

import com.vesoft.exchange.common.config.SourceCategory
import com.vesoft.exchange.common.utils.CompanionUtils
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
      val configResolverName = "com.vesoft.nebula.exchange.plugin.fileBase.ConfigResolverImpl"
      val readerClazzName    = "com.vesoft.nebula.exchange.plugin.fileBase.CustomReaderImpl"
      //val name = "com.vesoft.nebula.exchange.plugin.fileBase.ParquetDataSourcePlugin"
      val resolver = CompanionUtils.lookupCompanion[DataSourceConfigResolver](configResolverName)
      val reader   = CompanionUtils.lookupCompanion[DataSourceCustomReader](readerClazzName)
      //assert load success
      assert(resolver.getClass.getName == configResolverName)
      assert(reader.getClass.getName == readerClazzName)

      //assert class is the same
      val resolver2 = CompanionUtils.lookupCompanion[DataSourceConfigResolver](configResolverName)
      val reader2   = CompanionUtils.lookupCompanion[DataSourceCustomReader](readerClazzName)

      assert(resolver2.getClass.getName == configResolverName)
      assert(resolver.getClass.getClassLoader == resolver2.getClass.getClassLoader)
      assert(reader2.getClass.getName == readerClazzName)
      assert(reader.getClass.getClassLoader == reader2.getClass.getClassLoader)

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
