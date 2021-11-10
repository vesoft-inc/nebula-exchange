
/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange.processor

import com.vesoft.nebula.{Date, DateTime, NullType, Time, Value, Geography, Coordinate, Point, LineString, Polygon}
import com.vesoft.nebula.exchange.utils.NebulaUtils.DEFAULT_EMPTY_VALUE
import com.vesoft.nebula.exchange.utils.{HDFSUtils, NebulaUtils}
import com.vesoft.nebula.meta.PropertyType
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{IntegerType, LongType, StringType}

import org.locationtech.jts.io.WKBWriter;

/**
  * processor is a converter.
  * It is responsible for converting the dataframe row data into Nebula Graph's vertex or edge,
  * and submit data to writer.
  */
trait Processor extends Serializable {

  /**
    * process dataframe to vertices or edges
    */
  def process(): Unit

  /**
    * handle special types of attributes
    *
    * String type： add "" for attribute value， if value contains escape symbol，then keep it.
    *
    * Date type: add date() function for attribute value.
    * eg: convert attribute value 2020-01-01 to date("2020-01-01")
    *
    * Time type: add time() function for attribute value.
    * eg: convert attribute value 12:12:12:1111 to time("12:12:12:1111")
    *
    * DataTime type: add datetime() function for attribute value.
    * eg: convert attribute value 2020-01-01T22:30:40 to datetime("2020-01-01T22:30:40")
    */
  def extraValueForClient(row: Row, field: String, fieldTypeMap: Map[String, Int]): Any = {
    val index = row.schema.fieldIndex(field)

    if (row.isNullAt(index)) return null

    PropertyType.findByValue(fieldTypeMap(field)) match {
      case PropertyType.STRING | PropertyType.FIXED_STRING => {
        var value = row.get(index).toString
        if (value.equals(DEFAULT_EMPTY_VALUE)) {
          value = ""
        }
        val result = NebulaUtils.escapeUtil(value).mkString("\"", "", "\"")
        result
      }
      case PropertyType.DATE     => "date(\"" + row.get(index) + "\")"
      case PropertyType.DATETIME => "datetime(\"" + row.get(index) + "\")"
      case PropertyType.TIME     => "time(\"" + row.get(index) + "\")"
      case PropertyType.TIMESTAMP => {
        val value = row.get(index).toString
        if (NebulaUtils.isNumic(value)) {
          value
        } else {
          "timestamp(\"" + row.get(index) + "\")"
        }
      }
      case PropertyType.GEOGRAPHY => "ST_GeogFromText(\"" + row.get(index) + "\")"
      case _                      => row.get(index)
    }
  }

  def extraValueForSST(row: Row, field: String, fieldTypeMap: Map[String, Int]): Any = {
    val index = row.schema.fieldIndex(field)
    if (row.isNullAt(index)) {
      val nullVal = new Value()
      nullVal.setNVal(NullType.__NULL__)
      return nullVal
    }

    PropertyType.findByValue(fieldTypeMap(field)) match {
      case PropertyType.UNKNOWN =>
        throw new IllegalArgumentException("date type in nebula is UNKNOWN.")
      case PropertyType.STRING | PropertyType.FIXED_STRING => {
        val value = row.get(index).toString
        if (value.equals(DEFAULT_EMPTY_VALUE)) "" else value
      }
      case PropertyType.BOOL                     => row.get(index).toString.toBoolean
      case PropertyType.DOUBLE                   => row.get(index).toString.toDouble
      case PropertyType.FLOAT                    => row.get(index).toString.toFloat
      case PropertyType.INT8                     => row.get(index).toString.toByte
      case PropertyType.INT16                    => row.get(index).toString.toShort
      case PropertyType.INT32                    => row.get(index).toString.toInt
      case PropertyType.INT64 | PropertyType.VID => row.get(index).toString.toLong
      case PropertyType.TIME => {
        val values = row.get(index).toString.split(":")
        if (values.size < 3) {
          throw new UnsupportedOperationException(
            s"wrong format for time value: ${row.get(index)}, correct format is 12:00:00:0000")
        }
        new Time(values(0).toByte,
                 values(1).toByte,
                 values(2).toByte,
                 if (values.length > 3) values(3).toInt else 0)
      }
      case PropertyType.DATE => {
        val values = row.get(index).toString.split("-")
        if (values.size < 3) {
          throw new UnsupportedOperationException(
            s"wrong format for date value: ${row.get(index)}, correct format is 2020-01-01")
        }
        new Date(values(0).toShort, values(1).toByte, values(2).toByte)
      }
      case PropertyType.DATETIME => {
        val rowValue                     = row.get(index).toString
        var dateTimeValue: Array[String] = null
        if (rowValue.contains("T")) {
          dateTimeValue = rowValue.split("T")
        } else if (rowValue.trim.contains(" ")) {
          dateTimeValue = rowValue.trim.split(" ")
        } else {
          throw new UnsupportedOperationException(
            s"wrong format for datetime value: $rowValue, " +
              s"correct format is 2020-01-01T12:00:00:0000 or 2020-01-01 12:00:00:0000")
        }

        if (dateTimeValue.size < 2) {
          throw new UnsupportedOperationException(
            s"wrong format for datetime value: $rowValue, " +
              s"correct format is 2020-01-01T12:00:00:0000 or 2020-01-01 12:00:00:0000")
        }

        val dateValues = dateTimeValue(0).split("-")
        val timeValues = dateTimeValue(1).split(":")

        if (dateValues.size < 3 || timeValues.size < 3) {
          throw new UnsupportedOperationException(
            s"wrong format for datetime value: $rowValue, " +
              s"correct format is 2020-01-01T12:00:00:0000 or 2020-01-01 12:00:00")
        }

        val microsec: Int = if (timeValues.size == 4) timeValues(3).toInt else 0
        new DateTime(dateValues(0).toShort,
                     dateValues(1).toByte,
                     dateValues(2).toByte,
                     timeValues(0).toByte,
                     timeValues(1).toByte,
                     timeValues(2).toByte,
                     microsec)
      }
      case PropertyType.TIMESTAMP => {
        val value = row.get(index).toString
        if (!NebulaUtils.isNumic(value)) {
          throw new IllegalArgumentException(
            s"timestamp only support long type, your value is ${value}")
        }
        row.get(index).toString.toLong
      }
      case PropertyType.GEOGRAPHY => {
        val wkt                     = row.get(index).toString
        org.locationtech.jts.geom.Geometry jtsGeom = new org.locationtech.jts.io
                         .WKTReader(2, ByteOrderValues.LITTLE_ENDIAN)
                         .read(wkt);
        convertJTSGeometryToGeography(jtsGeom)
      }
    }
  }

  def fetchOffset(path: String): Long = {
    HDFSUtils.getContent(path).toLong
  }

  def getLong(row: Row, field: String): Long = {
    val index = row.schema.fieldIndex(field)
    row.schema.fields(index).dataType match {
      case LongType    => row.getLong(index)
      case IntegerType => row.getInt(index).toLong
      case StringType  => row.getString(index).toLong
    }
  }

  def convertJTSGeometryToGeography(jtsGeom: org.locationtech.jts.geom.Geometry): Geography = {
    jtsGeom.getGeometryType() match {
      case Geometry.TYPENAME_POINT => {
        val jtsCoord = jtsGeom.getCoordinate()
        Geography.ptVal(new Point(new Coordinate(jtsCoord.x, jtsCoord.y)))
      }
      case Geometry.TYPENAME_LINESTRING => {
        val jtsCoordList = jtsGeom.getCoordinates().asScala
        val coordList = scala.collection.mutable.ListBuffer.empty[Coordinate]
        for (var jtsCoord <- jtsCoordList) {
          coordList += jtsCoord
        }
        Geography.lsVal(new LineString(coordList.asJava))
      }
      case Geometry.TYPENAME_POLYGON => {
        val coordListList = ListBuffer[ListBuffer[Coordinate]]
        val jtsShell = jtsGeom.getExteriorRing()
        val coordList = ListBuffer[Coordinate]
        for (var jtsCoord <- jtsShell.getCoordinates()) {
          coordList += new Coordinate(jtsCoord.x, jtsCoord.y)
        }
        coordListList += coordList

        val jtsHolesNum = jtsGeom.getNumInteriorRing()
        for (var i <- 0 to jtsHolesNum) {
          val coordList = ListBuffer[Coordinate]
          val jtsHole = jtsGeom.getInteriorRingN(i)
          for (var jtsCoord <- jtsHole.geteCoordinates()) {
            coordList += new Coordinate(jtsCoord.x, jtsCoord.y)
          }
          coordListList += coordList
        }
        Geography.pgVal(new Polygon(coordListList.asJava))
      }
    }
  }
}
