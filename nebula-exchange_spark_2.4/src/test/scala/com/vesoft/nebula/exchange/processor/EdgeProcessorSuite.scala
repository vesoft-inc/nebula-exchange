/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.common.processor

import java.io.File

import com.vesoft.nebula.PropertyType
import com.vesoft.nebula.common.VidType
import com.vesoft.nebula.common.KeyPolicy
import com.vesoft.nebula.common.config.{Configs, EdgeConfigEntry}
import com.vesoft.nebula.common.utils.NebulaUtils.DEFAULT_EMPTY_VALUE
import com.vesoft.nebula.exchange.processor.EdgeProcessor
import com.vesoft.nebula.meta.{ColumnDef, ColumnTypeDef, EdgeItem, Schema, SchemaProp, TagItem}
import org.apache.commons.codec.binary.Hex
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types.{
  BooleanType,
  DoubleType,
  IntegerType,
  LongType,
  ShortType,
  StringType,
  StructField,
  StructType
}
import org.junit.Test
import org.scalatest.Assertions.assertThrows

import scala.collection.JavaConverters._

class EdgeProcessorSuite {
  val config: Configs =
    Configs.parse(new File("../common/src/test/resources/process_application.conf"))

  var data: DataFrame             = null
  var edgeConfig: EdgeConfigEntry = config.edgesConfig.head
  val fieldKeys = List("col1",
                       "col2",
                       "col3",
                       "col4",
                       "col5",
                       "col6",
                       "col7",
                       "col8",
                       "col9",
                       "col10",
                       "col11",
                       "col12",
                       "col13",
                       "col14")
  val nebulaKeys = List("col1",
                        "col2",
                        "col3",
                        "col4",
                        "col5",
                        "col6",
                        "col7",
                        "col8",
                        "col9",
                        "col10",
                        "col11",
                        "col12",
                        "col13",
                        "col14")

  val processClazz =
    new EdgeProcessor(data, edgeConfig, fieldKeys, nebulaKeys, config, null, null)
  @Test
  def isEdgeValidSuite(): Unit = {
    val stringIdValue = List("Bob", "Tom")
    val intIdValue    = List("11", "12")
    val schema: StructType = StructType(
      List(StructField("src", StringType, nullable = true),
           StructField("dst", StringType, nullable = true)))
    val stringIdRow = new GenericRowWithSchema(stringIdValue.toArray, schema)
    val intIdRow    = new GenericRowWithSchema(intIdValue.toArray, schema)
    val edgeConfigEntry = EdgeConfigEntry("friend",
                                          null,
                                          null,
                                          fieldKeys,
                                          nebulaKeys,
                                          "src",
                                          None,
                                          None,
                                          "dst",
                                          None,
                                          false,
                                          None,
                                          None,
                                          10,
                                          10,
                                          None)

    // test for string id value without policy
    assert(processClazz.isEdgeValid(stringIdRow, edgeConfigEntry, false, true))
    assert(processClazz.isEdgeValid(stringIdRow, edgeConfigEntry, true, true))
    assert(!processClazz.isEdgeValid(stringIdRow, edgeConfigEntry, true, false))
    assertThrows[AssertionError](
      processClazz.isEdgeValid(stringIdRow, edgeConfigEntry, false, false))

    // test for int id value without policy
    assert(processClazz.isEdgeValid(intIdRow, edgeConfigEntry, false, false))
    assert(processClazz.isEdgeValid(intIdRow, edgeConfigEntry, true, false))
    assert(processClazz.isEdgeValid(intIdRow, edgeConfigEntry, true, true))
    assert(processClazz.isEdgeValid(intIdRow, edgeConfigEntry, false, true))

    // test for string id value with policy
    val edgeConfigEntryWithPolicy = EdgeConfigEntry("friend",
                                                    null,
                                                    null,
                                                    fieldKeys,
                                                    nebulaKeys,
                                                    "src",
                                                    Some(KeyPolicy.HASH),
                                                    None,
                                                    "dst",
                                                    Some(KeyPolicy.HASH),
                                                    false,
                                                    None,
                                                    None,
                                                    10,
                                                    10,
                                                    None)
    assert(!processClazz.isEdgeValid(stringIdRow, edgeConfigEntryWithPolicy, true, true))
    assertThrows[AssertionError](
      processClazz.isEdgeValid(stringIdRow, edgeConfigEntryWithPolicy, false, true))

    // test for int id value with policy
    assert(processClazz.isEdgeValid(stringIdRow, edgeConfigEntryWithPolicy, true, false))
    assert(!processClazz.isEdgeValid(stringIdRow, edgeConfigEntryWithPolicy, true, true))
    assert(processClazz.isEdgeValid(stringIdRow, edgeConfigEntryWithPolicy, false, false))
    assertThrows[AssertionError](
      processClazz.isEdgeValid(stringIdRow, edgeConfigEntryWithPolicy, false, true))
  }

  @Test
  def convertToEdgeSuite(): Unit = {
    val row  = getRow()
    val map  = getFieldType()
    val edge = processClazz.convertToEdge(row, edgeConfig, true, fieldKeys, map)
    assert(edge.source.equals("\"1\""))
    assert(edge.destination.equals("\"2\""))
    assert(edge.toString.equals(
      "Edge: \"1\"->\"2\"@0 values: \"\", \"fixedBob\", 12, 200, 1000, 100000, date(\"2021-01-01\"), datetime(\"2021-01-01T12:00:00.100\"), time(\"12:00:00.100\"), 345436232, true, 12.01, 22.12, ST_GeogFromText(\"POINT(3 8)\")"))
  }

  @Test
  def encodeEdgeSuite(): Unit = {
    val row = getRow()
    val columns = List(
      new ColumnDef("col1".getBytes(), new ColumnTypeDef(PropertyType.STRING)),
      new ColumnDef("col2".getBytes(), new ColumnTypeDef(PropertyType.STRING)),
      new ColumnDef("col3".getBytes(), new ColumnTypeDef(PropertyType.INT8)),
      new ColumnDef("col4".getBytes(), new ColumnTypeDef(PropertyType.INT16)),
      new ColumnDef("col5".getBytes(), new ColumnTypeDef(PropertyType.INT32)),
      new ColumnDef("col6".getBytes(), new ColumnTypeDef(PropertyType.INT64)),
      new ColumnDef("col7".getBytes(), new ColumnTypeDef(PropertyType.DATE)),
      new ColumnDef("col8".getBytes(), new ColumnTypeDef(PropertyType.DATETIME)),
      new ColumnDef("col9".getBytes(), new ColumnTypeDef(PropertyType.TIME)),
      new ColumnDef("col10".getBytes(), new ColumnTypeDef(PropertyType.TIMESTAMP)),
      new ColumnDef("col11".getBytes(), new ColumnTypeDef(PropertyType.BOOL)),
      new ColumnDef("col12".getBytes(), new ColumnTypeDef(PropertyType.DOUBLE)),
      new ColumnDef("col13".getBytes(), new ColumnTypeDef(PropertyType.FLOAT)),
      new ColumnDef("col14".getBytes(), new ColumnTypeDef(PropertyType.GEOGRAPHY))
    )
    val schema   = new Schema(columns.asJava, new SchemaProp())
    val edgeItem = new EdgeItem(2, "friend".getBytes(), -1, schema)
    val map      = getFieldType()

    val (key1, key2, value) = processClazz.encodeEdge(row, 10, VidType.STRING, 10, edgeItem, map)

    val keyHex1  = Hex.encodeHexString(key1)
    val keyHex2  = Hex.encodeHexString(key2)
    val valueHex = Hex.encodeHexString(value)
    assert(
      keyHex1.equals("02060000310000000000000000000200000080000000000000003200000000000000000001"))
    assert(
      keyHex2.equals("0201000032000000000000000000feffffff80000000000000003100000000000000000001"))
  }

  private def getRow(): Row = {
    val values = List(
      "1",
      "2",
      DEFAULT_EMPTY_VALUE,
      "fixedBob",
      12,
      200,
      1000,
      100000,
      "2021-01-01",
      "2021-01-01T12:00:00.100",
      "12:00:00.100",
      "345436232",
      true,
      12.01,
      22.12,
      "POINT(3 8)"
    )
    val schema: StructType = StructType(
      List(
        StructField("src", StringType, nullable = false),
        StructField("dst", StringType, nullable = false),
        StructField("col1", StringType, nullable = true),
        StructField("col2", StringType, nullable = true),
        StructField("col3", ShortType, nullable = true),
        StructField("col4", ShortType, nullable = true),
        StructField("col5", IntegerType, nullable = true),
        StructField("col6", LongType, nullable = true),
        StructField("col7", StringType, nullable = true),
        StructField("col8", StringType, nullable = true),
        StructField("col9", StringType, nullable = true),
        StructField("col10", StringType, nullable = true),
        StructField("col11", BooleanType, nullable = true),
        StructField("col12", DoubleType, nullable = true),
        StructField("col13", DoubleType, nullable = true),
        StructField("col14", StringType, nullable = true)
      ))
    val row = new GenericRowWithSchema(values.toArray, schema)
    row
  }

  private def getFieldType(): Map[String, Int] = {
    val map = Map(
      "col1"  -> PropertyType.STRING.getValue,
      "col2"  -> PropertyType.STRING.getValue,
      "col3"  -> PropertyType.INT8.getValue,
      "col4"  -> PropertyType.INT16.getValue,
      "col5"  -> PropertyType.INT32.getValue,
      "col6"  -> PropertyType.INT64.getValue,
      "col7"  -> PropertyType.DATE.getValue,
      "col8"  -> PropertyType.DATETIME.getValue,
      "col9"  -> PropertyType.TIME.getValue,
      "col10" -> PropertyType.TIMESTAMP.getValue,
      "col11" -> PropertyType.BOOL.getValue,
      "col12" -> PropertyType.DOUBLE.getValue,
      "col13" -> PropertyType.FLOAT.getValue,
      "col14" -> PropertyType.GEOGRAPHY.getValue
    )
    map
  }
}
