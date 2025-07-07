package com.vesoft.exchange.common.plugin

import com.vesoft.exchange.Argument
import com.vesoft.exchange.common.config.{Configs, CustomSourceConfigEntry, SourceCategory}
import com.vesoft.exchange.common.utils.CompanionUtils
import org.apache.log4j.Logger
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.junit.{Before, Test}

import java.io.File
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader


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

    try {
      val args    = List("-c", "src/test/resources/custom_csv_application.conf")
      val options = Configs.parser(args.toArray, "test")
      val c: Argument = options match {
        case Some(config) => config
        case _ =>
          assert(false)
          sys.exit(-1)
      }

      val configs             = Configs.parse(c.config)
      val tagsConfig          = configs.tagsConfig

      val playerTagConfigEntry = tagsConfig.head
      val sourceConfigEntry    = playerTagConfigEntry.dataSourceConfigEntry.asInstanceOf[CustomSourceConfigEntry]
      assert(sourceConfigEntry.category == SourceCategory.CUSTOM)

      LOG.info(sourceConfigEntry.readerClazz)
      LOG.info(sourceConfigEntry.rawConfig)
      LOG.info(sourceConfigEntry.nebulaConfig)

      assert(sourceConfigEntry.readerClazz == "com.vesoft.nebula.exchange.plugin.fileBase.CustomReaderImpl")

      val reader = CompanionUtils.lookupCompanion[DataSourceCustomReader](sourceConfigEntry.readerClazz)
      assert(reader.getClass.getName == "com.vesoft.nebula.exchange.plugin.fileBase.CustomReaderImpl$")

      LOG.info(">>>>> test end")
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
