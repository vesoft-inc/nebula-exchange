/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.config

import com.vesoft.exchange.common.KeyPolicy

/**
  * SchemaConfigEntry is tag/edge super class use to save some basic parameter for importer.
  */
sealed trait SchemaConfigEntry {

  /** nebula tag or edge name */
  def name: String

  /** see{@link DataSourceConfigEntry}*/
  def dataSourceConfigEntry: DataSourceConfigEntry

  /** see{@link DataSinkConfigEntry}*/
  def dataSinkConfigEntry: DataSinkConfigEntry

  /** data source fields which are going to be import to nebula as properties */
  def fields: List[String]

  /** nebula properties which are going to fill value with data source value*/
  def nebulaFields: List[String]

  /** vertex or edge amount of one batch import */
  def batch: Int

  /** spark partition */
  def partition: Int

  /** check point path */
  def checkPointPath: Option[String]

  /** write mode */
  def writeMode: WriteMode.Mode
}

/**
  *
  * @param name
  * @param dataSourceConfigEntry
  * @param dataSinkConfigEntry
  * @param fields
  * @param nebulaFields
  * @param vertexField
  * @param vertexPolicy
  * @param batch
  * @param partition
  * @param checkPointPath
  */
case class TagConfigEntry(override val name: String,
                          override val dataSourceConfigEntry: DataSourceConfigEntry,
                          override val dataSinkConfigEntry: DataSinkConfigEntry,
                          override val fields: List[String],
                          override val nebulaFields: List[String],
                          override val writeMode: WriteMode.Mode,
                          vertexField: String,
                          vertexPolicy: Option[KeyPolicy.Value],
                          vertexPrefix: String,
                          override val batch: Int,
                          override val partition: Int,
                          override val checkPointPath: Option[String],
                          repartitionWithNebula: Boolean = true,
                          enableTagless: Boolean = false,
                          ignoreIndex: Boolean = false,
                          deleteEdge: Boolean = false,
                          vertexUdf: Option[UdfConfigEntry] = None)
    extends SchemaConfigEntry {
  require(name.trim.nonEmpty, "tag name cannot be empty")
  require(vertexField.trim.nonEmpty, "tag vertex id cannot be empty")
  require(batch > 0, "batch config must be larger than 0")
  require(fields.size == nebulaFields.size,
          "fields and nebula.fields must have the same element number")

  override def toString: String = {
    s"Tag name: $name, " +
      s"source: $dataSourceConfigEntry, " +
      s"sink: $dataSinkConfigEntry, " +
      s"writeMode: $writeMode, " +
      s"vertex field: $vertexField, " +
      s"vertex policy: $vertexPolicy, " +
      s"batch: $batch, " +
      s"partition: $partition, " +
      s"repartitionWithNebula: $repartitionWithNebula, " +
      s"enableTagless: $enableTagless, " +
      s"ignoreIndex: $ignoreIndex, " +
      s"vertexUdf: $vertexUdf."
  }
}

/**
  *
  * @param name
  * @param dataSourceConfigEntry
  * @param dataSinkConfigEntry
  * @param fields
  *  @param nebulaFields
  * @param sourceField
  * @param sourcePolicy
  * @param rankingField
  * @param targetField
  * @param targetPolicy
  * @param isGeo
  * @param latitude
  * @param longitude
  * @param batch
  * @param partition
  * @param checkPointPath
  */
case class EdgeConfigEntry(override val name: String,
                           override val dataSourceConfigEntry: DataSourceConfigEntry,
                           override val dataSinkConfigEntry: DataSinkConfigEntry,
                           override val fields: List[String],
                           override val nebulaFields: List[String],
                           override val writeMode: WriteMode.Mode,
                           sourceField: String,
                           sourcePolicy: Option[KeyPolicy.Value],
                           sourcePrefix: String,
                           rankingField: Option[String],
                           targetField: String,
                           targetPolicy: Option[KeyPolicy.Value],
                           targetPrefix: String,
                           isGeo: Boolean,
                           latitude: Option[String],
                           longitude: Option[String],
                           override val batch: Int,
                           override val partition: Int,
                           override val checkPointPath: Option[String],
                           repartitionWithNebula: Boolean = false,
                           ignoreIndex: Boolean = false,
                           srcVertexUdf: Option[UdfConfigEntry] = None,
                           dstVertexUdf: Option[UdfConfigEntry] = None)
    extends SchemaConfigEntry {
  require(name.trim.nonEmpty, "edge name cannot be empty")
  require(sourceField.trim.nonEmpty, "edge source id cannot be empty")
  require(targetField.trim.nonEmpty, "edge target id cannot be empty")
  require(batch > 0, "batch config must be larger than 0")
  require(fields.size == nebulaFields.size,
    "fields and nebula.fields must have the same element number")

  override def toString: String = {
    if (isGeo) {
      s"Edge name: $name, " +
        s"source: $dataSourceConfigEntry, " +
        s"sink: $dataSinkConfigEntry, " +
        s"writeMode: $writeMode, " +
        s"latitude: $latitude, " +
        s"longitude: $longitude, " +
        s"source field: $sourceField, " +
        s"source policy: $sourcePolicy, " +
        s"ranking: $rankingField, " +
        s"target field: $targetField, " +
        s"target policy: $targetPolicy, " +
        s"batch: $batch, " +
        s"partition: $partition, " +
        s"ignoreIndex: $ignoreIndex, " +
        s"srcVertexUdf: $srcVertexUdf" +
        s"dstVertexUdf: $dstVertexUdf."
    } else {
      s"Edge name: $name, " +
        s"source: $dataSourceConfigEntry, " +
        s"sink: $dataSinkConfigEntry, " +
        s"writeMode: $writeMode, " +
        s"source field: $sourceField, " +
        s"source policy: $sourcePolicy, " +
        s"ranking: $rankingField, " +
        s"target field: $targetField, " +
        s"target policy: $targetPolicy, " +
        s"batch: $batch, " +
        s"partition: $partition, " +
        s"ignoreIndex: $ignoreIndex, " +
        s"srcVertexUdf: $srcVertexUdf" +
        s"dstVertexUdf: $dstVertexUdf."
    }
  }
}
