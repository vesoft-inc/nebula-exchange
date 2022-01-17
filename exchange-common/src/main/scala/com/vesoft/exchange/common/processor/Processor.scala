/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.processor

import com.vesoft.exchange.common.VidType
import com.vesoft.exchange.common.utils.{HDFSUtils, NebulaPartitioner, NebulaUtils}
import com.vesoft.exchange.common.utils.NebulaUtils.DEFAULT_EMPTY_VALUE
import com.vesoft.nebula.{
  Coordinate,
  Date,
  DateTime,
  Geography,
  LineString,
  NullType,
  Point,
  Polygon,
  PropertyType,
  Time,
  Value
}
import org.apache.log4j.Logger
import org.apache.spark.sql.{DataFrame, Dataset, Encoders, Row, SparkSession}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
  * com.vesoft.exchange.common.processor is a converter.
  * It is responsible for converting the dataframe row data into Nebula Graph's vertex or edge,
  * and submit data to writer.
  */
trait Processor extends Serializable {

  @transient
  private[this] lazy val LOG = Logger.getLogger(this.getClass)

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
    * eg: convert attribute value 12:12:12.1111 to time("12:12:12.1111")
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
        val secs: Array[String] = values(2).split("\\.")
        val sec: Byte           = secs(0).toByte
        val microSec: Int       = if (secs.length == 2) secs(1).toInt else 0
        new Time(values(0).toByte, values(1).toByte, sec, microSec)
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
              s"correct format is 2020-01-01T12:00:00.0000 or 2020-01-01 12:00:00.0000")
        }

        if (dateTimeValue.size < 2) {
          throw new UnsupportedOperationException(
            s"wrong format for datetime value: $rowValue, " +
              s"correct format is 2020-01-01T12:00:00.0000 or 2020-01-01 12:00:00.0000")
        }

        val dateValues = dateTimeValue(0).split("-")
        val timeValues = dateTimeValue(1).split(":")

        if (dateValues.size < 3 || timeValues.size < 3) {
          throw new UnsupportedOperationException(
            s"wrong format for datetime value: $rowValue, " +
              s"correct format is 2020-01-01T12:00:00.0000 or 2020-01-01 12:00:00")
        }

        val secs: Array[String] = timeValues(2).split("\\.")
        val sec: Byte           = secs(0).toByte
        val microsec: Int       = if (secs.length == 2) secs(1).toInt else 0
        new DateTime(dateValues(0).toShort,
                     dateValues(1).toByte,
                     dateValues(2).toByte,
                     timeValues(0).toByte,
                     timeValues(1).toByte,
                     sec,
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
        val wkt     = row.get(index).toString
        val jtsGeom = new org.locationtech.jts.io.WKTReader().read(wkt)
        convertJTSGeometryToGeography(jtsGeom)
      }
      case PropertyType.DURATION => {
        throw new IllegalArgumentException("do not support data type duration.")
      }
    }
  }

  def fetchOffset(path: String): Long = {
    HDFSUtils.getContent(path).toLong
  }

  def convertJTSGeometryToGeography(jtsGeom: org.locationtech.jts.geom.Geometry): Geography = {
    jtsGeom.getGeometryType match {
      case "Point" => {
        val jtsPoint = jtsGeom.asInstanceOf[org.locationtech.jts.geom.Point]
        val jtsCoord = jtsPoint.getCoordinate
        Geography.ptVal(new Point(new Coordinate(jtsCoord.x, jtsCoord.y)))
      }
      case "LineString" => {
        val jtsLineString = jtsGeom.asInstanceOf[org.locationtech.jts.geom.LineString]
        val jtsCoordList  = jtsLineString.getCoordinates
        val coordList     = new ListBuffer[Coordinate]()
        for (jtsCoord <- jtsCoordList) {
          coordList += new Coordinate(jtsCoord.x, jtsCoord.y)
        }
        Geography.lsVal(new LineString(coordList.asJava))
      }
      case "Polygon" => {
        val jtsPolygon    = jtsGeom.asInstanceOf[org.locationtech.jts.geom.Polygon]
        val coordListList = new java.util.ArrayList[java.util.List[Coordinate]]()
        val jtsShell      = jtsPolygon.getExteriorRing
        val coordList     = new ListBuffer[Coordinate]()
        for (jtsCoord <- jtsShell.getCoordinates) {
          coordList += new Coordinate(jtsCoord.x, jtsCoord.y)
        }
        coordListList.add(coordList.asJava)

        val jtsHolesNum = jtsPolygon.getNumInteriorRing
        for (i <- 0 until jtsHolesNum) {
          val coordList = new ListBuffer[Coordinate]()
          val jtsHole   = jtsPolygon.getInteriorRingN(i)
          for (jtsCoord <- jtsHole.getCoordinates) {
            coordList += new Coordinate(jtsCoord.x, jtsCoord.y)
          }
          coordListList.add(coordList.asJava)
        }
        Geography.pgVal(new Polygon(coordListList))
      }
    }
  }

  def printChoice(streamFlag: Boolean, context: String): Unit = {
    if (streamFlag) LOG.warn(context)
    else assert(assertion = false, context)
  }

  def customRepartition(spark: SparkSession,
                        data: Dataset[(Array[Byte], Array[Byte])],
                        partitionNum: Int): Dataset[(Array[Byte], Array[Byte])] = {
    import spark.implicits._
    data.rdd
      .partitionBy(new NebulaPartitioner(partitionNum))
      .map(kv => SSTData(kv._1, kv._2))
      .toDF()
      .map { row =>
        (row.getAs[Array[Byte]](0), row.getAs[Array[Byte]](1))
      }(Encoders.tuple(Encoders.BINARY, Encoders.BINARY))
  }

}

case class SSTData(key: Array[Byte], value: Array[Byte])
