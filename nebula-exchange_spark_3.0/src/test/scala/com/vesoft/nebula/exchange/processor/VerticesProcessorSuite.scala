/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange.processor

import java.io.File
import com.vesoft.exchange.common.VidType
import com.vesoft.nebula.PropertyType
import com.vesoft.exchange.common.KeyPolicy
import com.vesoft.exchange.common.config.{Configs, TagConfigEntry, WriteMode}
import com.vesoft.exchange.common.utils.NebulaUtils.DEFAULT_EMPTY_VALUE
import com.vesoft.nebula.meta.{ColumnDef, ColumnTypeDef, Schema, SchemaProp, TagItem}
import org.apache.commons.codec.binary.Hex
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types.{BooleanType, DoubleType, IntegerType, LongType, ShortType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import org.junit.Test
import org.scalatest.Assertions.assertThrows

import scala.collection.JavaConverters._

class VerticesProcessorSuite {
  val config: Configs =
    Configs.parse("../exchange-common/src/test/resources/process_application.conf")

  var data: DataFrame           = null
  var tagConfig: TagConfigEntry = config.tagsConfig.head
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
    new VerticesProcessor(null, data, tagConfig, fieldKeys, nebulaKeys, config, null, null)
  @Test
  def isVertexValidSuite(): Unit = {
    val stringIdValue      = List("Bob")
    val intIdValue         = List("11")
    val schema: StructType = StructType(List(StructField("id", StringType, nullable = true)))
    val stringIdRow        = new GenericRowWithSchema(stringIdValue.toArray, schema)
    val intIdRow           = new GenericRowWithSchema(intIdValue.toArray, schema)
    val writerMode         = WriteMode.INSERT
    val tagConfigEntry =
      TagConfigEntry("person", null, null, List(), List(), writeMode, "id", None, null, 10, 10, None)

    // test for string id value without policy
    assert(processClazz.isVertexValid(stringIdRow, tagConfigEntry, false, true))
    assert(processClazz.isVertexValid(stringIdRow, tagConfigEntry, true, true))
    assert(!processClazz.isVertexValid(stringIdRow, tagConfigEntry, true, false))
    assertThrows[AssertionError](
      processClazz.isVertexValid(stringIdRow, tagConfigEntry, false, false))

    // test for int id value without policy
    assert(processClazz.isVertexValid(intIdRow, tagConfigEntry, false, false))
    assert(processClazz.isVertexValid(intIdRow, tagConfigEntry, true, false))
    assert(processClazz.isVertexValid(intIdRow, tagConfigEntry, true, true))
    assert(processClazz.isVertexValid(intIdRow, tagConfigEntry, false, true))

    // test for string id value with policy
    val tagConfigEntryWithPolicy =
      TagConfigEntry("person",
                     null,
                     null,
                     List(),
                     List(),
                     writeMode,
                     "id",
                     Some(KeyPolicy.HASH),
                     null,
                     10,
                     10,
                     None)
    assert(!processClazz.isVertexValid(stringIdRow, tagConfigEntryWithPolicy, true, true))
    assertThrows[AssertionError](
      processClazz.isVertexValid(stringIdRow, tagConfigEntryWithPolicy, false, true))

    // test for int id value with policy
    assert(processClazz.isVertexValid(stringIdRow, tagConfigEntryWithPolicy, true, false))
    assert(!processClazz.isVertexValid(stringIdRow, tagConfigEntryWithPolicy, true, true))
    assert(processClazz.isVertexValid(stringIdRow, tagConfigEntryWithPolicy, false, false))
    assertThrows[AssertionError](
      processClazz.isVertexValid(stringIdRow, tagConfigEntryWithPolicy, false, true))
  }

  @Test
  def convertToVertexSuite(): Unit = {
    val row    = getRow()
    val map    = getFieldType()
    val vertex = processClazz.convertToVertex(row, tagConfig, true, fieldKeys, map)
    assert(vertex.vertexID.equals("\"1\""))
    assert(vertex.toString.equals(
      "Vertex ID: \"1\", Values: \"\", \"fixedBob\", 12, 200, 1000, 100000, date(\"2021-01-01\"), datetime(\"2021-01-01T12:00:00.100\"), time(\"12:00:00.100\"), 345436232, true, 12.01, 22.12, ST_GeogFromText(\"POINT(3 8)\")"))
  }

  @Test
  def encodeVertexSuite(): Unit = {
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
    val schema  = new Schema(columns.asJava, new SchemaProp())
    val tagItem = new TagItem(1, "person".getBytes(), -1, schema)
    val map     = getFieldType()

    val (key, value) =
      processClazz.encodeVertex(row, 10, VidType.STRING, 10, tagItem, map)

    val keyHex   = Hex.encodeHexString(key)
    val valueHex = Hex.encodeHexString(value)
    assert(keyHex.equals("010600003100000000000000000001000000"))
  }

  private def getRow(): Row = {
    val values = List(
      "1",
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
        StructField("id", StringType, nullable = false),
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
