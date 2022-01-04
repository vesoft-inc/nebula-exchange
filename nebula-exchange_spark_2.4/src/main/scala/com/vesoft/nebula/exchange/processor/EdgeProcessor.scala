/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange.processor

import java.nio.ByteOrder

import com.google.common.geometry.{S2CellId, S2LatLng}
import com.vesoft.exchange.common.{ErrorHandler, GraphProvider, MetaProvider, VidType}
import com.vesoft.exchange.common.{Edge, Edges, KeyPolicy}
import com.vesoft.exchange.common.config.{
  Configs,
  EdgeConfigEntry,
  FileBaseSinkConfigEntry,
  SinkCategory,
  StreamingDataSourceConfigEntry
}
import com.vesoft.exchange.common.processor.Processor
import com.vesoft.exchange.common.utils.NebulaUtils
import com.vesoft.exchange.common.utils.NebulaUtils.DEFAULT_EMPTY_VALUE
import com.vesoft.exchange.common.writer.{NebulaGraphClientWriter, NebulaSSTWriter}
import com.vesoft.exchange.common.VidType
import com.vesoft.nebula.encoder.NebulaCodecImpl
import com.vesoft.nebula.meta.EdgeItem
import org.apache.commons.codec.digest.MurmurHash2
import org.apache.log4j.Logger
import org.apache.spark.TaskContext
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.{DataFrame, Encoders, Row}
import org.apache.spark.util.LongAccumulator

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

class EdgeProcessor(data: DataFrame,
                    edgeConfig: EdgeConfigEntry,
                    fieldKeys: List[String],
                    nebulaKeys: List[String],
                    config: Configs,
                    batchSuccess: LongAccumulator,
                    batchFailure: LongAccumulator)
    extends Processor {

  @transient
  private[this] lazy val LOG = Logger.getLogger(this.getClass)

  private[this] val DEFAULT_MIN_CELL_LEVEL = 10
  private[this] val DEFAULT_MAX_CELL_LEVEL = 18

  private def processEachPartition(iterator: Iterator[Edge]): Unit = {
    val graphProvider =
      new GraphProvider(config.databaseConfig.getGraphAddress,
                        config.connectionConfig.timeout,
                        config.sslConfig)
    val writer = new NebulaGraphClientWriter(config.databaseConfig,
                                             config.userConfig,
                                             config.rateConfig,
                                             edgeConfig,
                                             graphProvider)
    val errorBuffer = ArrayBuffer[String]()

    writer.prepare()
    // batch write tags
    val startTime = System.currentTimeMillis
    iterator.grouped(edgeConfig.batch).foreach { edge =>
      val edges         = Edges(nebulaKeys, edge.toList, edgeConfig.sourcePolicy, edgeConfig.targetPolicy)
      val failStatement = writer.writeEdges(edges)
      if (failStatement == null) {
        batchSuccess.add(1)
      } else {
        errorBuffer.append(failStatement)
        batchFailure.add(1)
      }
    }
    if (errorBuffer.nonEmpty) {
      ErrorHandler.save(
        errorBuffer,
        s"${config.errorConfig.errorPath}/${edgeConfig.name}.${TaskContext.getPartitionId}")
      errorBuffer.clear()
    }
    LOG.info(s"edge ${edgeConfig.name} import in spark partition ${TaskContext
      .getPartitionId()} cost ${System.currentTimeMillis() - startTime}ms")
    writer.close()
    graphProvider.close()
  }

  override def process(): Unit = {

    val address = config.databaseConfig.getMetaAddress
    val space   = config.databaseConfig.space

    val timeout         = config.connectionConfig.timeout
    val retry           = config.connectionConfig.retry
    val metaProvider    = new MetaProvider(address, timeout, retry, config.sslConfig)
    val fieldTypeMap    = NebulaUtils.getDataSourceFieldType(edgeConfig, space, metaProvider)
    val isVidStringType = metaProvider.getVidType(space) == VidType.STRING
    val partitionNum    = metaProvider.getPartNumber(space)

    if (edgeConfig.dataSinkConfigEntry.category == SinkCategory.SST) {
      val fileBaseConfig = edgeConfig.dataSinkConfigEntry.asInstanceOf[FileBaseSinkConfigEntry]
      val namenode       = fileBaseConfig.fsName.orNull
      val edgeName       = edgeConfig.name

      val vidType     = metaProvider.getVidType(space)
      val spaceVidLen = metaProvider.getSpaceVidLen(space)
      val edgeItem    = metaProvider.getEdgeItem(space, edgeName)

      val distintData = if (edgeConfig.rankingField.isDefined) {
        data.dropDuplicates(edgeConfig.sourceField,
                            edgeConfig.targetField,
                            edgeConfig.rankingField.get)
      } else {
        data.dropDuplicates(edgeConfig.sourceField, edgeConfig.targetField)
      }
      distintData
        .mapPartitions { iter =>
          iter.map { row =>
            encodeEdge(row, partitionNum, vidType, spaceVidLen, edgeItem, fieldTypeMap)
          }
        }(Encoders.tuple(Encoders.BINARY, Encoders.BINARY, Encoders.BINARY))
        .flatMap(line => {
          List((line._1, line._3), (line._2, line._3))
        })(Encoders.tuple(Encoders.BINARY, Encoders.BINARY))
        .toDF("key", "value")
        .sortWithinPartitions("key")
        .foreachPartition { iterator: Iterator[Row] =>
          val sstFileWriter = new NebulaSSTWriter
          sstFileWriter.writeSstFiles(iterator,
                                      fileBaseConfig,
                                      partitionNum,
                                      namenode,
                                      batchFailure)
        }
    } else {
      val streamFlag = data.isStreaming
      val edgeFrame = data
        .filter { row =>
          isEdgeValid(row, edgeConfig, streamFlag, isVidStringType)
        }
        .map { row =>
          convertToEdge(row, edgeConfig, isVidStringType, fieldKeys, fieldTypeMap)
        }(Encoders.kryo[Edge])

      // streaming write
      if (streamFlag) {
        val streamingDataSourceConfig =
          edgeConfig.dataSourceConfigEntry.asInstanceOf[StreamingDataSourceConfigEntry]
        val wStream = edgeFrame.writeStream
        if (edgeConfig.checkPointPath.isDefined)
          wStream.option("checkpointLocation", edgeConfig.checkPointPath.get)

        wStream
          .foreachBatch((edges, batchId) => {
            LOG.info(s"${edgeConfig.name} edge start batch ${batchId}.")
            edges.foreachPartition(processEachPartition _)
          })
          .trigger(Trigger.ProcessingTime(s"${streamingDataSourceConfig.intervalSeconds} seconds"))
          .start()
          .awaitTermination()
      } else
        edgeFrame.foreachPartition(processEachPartition _)
    }
  }

  private[this] def indexCells(lat: Double, lng: Double): IndexedSeq[Long] = {
    val coordinate = S2LatLng.fromDegrees(lat, lng)
    val s2CellId   = S2CellId.fromLatLng(coordinate)
    for (index <- DEFAULT_MIN_CELL_LEVEL to DEFAULT_MAX_CELL_LEVEL)
      yield s2CellId.parent(index).id()
  }

  /**
    * filter and check row data for edge, if streaming only print log
    */
  def isEdgeValid(row: Row,
                  edgeConfig: EdgeConfigEntry,
                  streamFlag: Boolean,
                  isVidStringType: Boolean): Boolean = {
    val sourceFlag = checkField(edgeConfig.sourceField,
                                "source_field",
                                row,
                                edgeConfig.sourcePolicy,
                                streamFlag,
                                isVidStringType)

    val targetFlag = checkField(edgeConfig.targetField,
                                "target_field",
                                row,
                                edgeConfig.targetPolicy,
                                streamFlag,
                                isVidStringType)

    val edgeRankFlag = if (edgeConfig.rankingField.isDefined) {
      val index = row.schema.fieldIndex(edgeConfig.rankingField.get)
      if (index < 0 || row.isNullAt(index)) {
        printChoice(streamFlag, s"rank must exist and cannot be null, your row data is $row")
      }
      val ranking = row.get(index).toString
      if (!NebulaUtils.isNumic(ranking)) {
        printChoice(streamFlag,
                    s"Not support non-Numeric type for ranking field.your row data is $row")
        false
      } else true
    } else true
    sourceFlag && targetFlag && edgeRankFlag
  }

  /**
    * check if edge source id and target id valid
    */
  def checkField(field: String,
                 fieldType: String,
                 row: Row,
                 policy: Option[KeyPolicy.Value],
                 streamFlag: Boolean,
                 isVidStringType: Boolean): Boolean = {
    val fieldValue = if (edgeConfig.isGeo && "source_field".equals(fieldType)) {
      val lat = row.getDouble(row.schema.fieldIndex(edgeConfig.latitude.get))
      val lng = row.getDouble(row.schema.fieldIndex(edgeConfig.longitude.get))
      Some(indexCells(lat, lng).mkString(","))
    } else {
      val index = row.schema.fieldIndex(field)
      if (index < 0 || row.isNullAt(index)) {
        printChoice(streamFlag, s"$fieldType must exist and cannot be null, your row data is $row")
        None
      } else Some(row.get(index).toString)
    }

    val idFlag = fieldValue.isDefined
    val policyFlag =
      if (idFlag && policy.isEmpty && !isVidStringType
          && !NebulaUtils.isNumic(fieldValue.get)) {
        printChoice(
          streamFlag,
          s"space vidType is int, but your $fieldType $fieldValue is not numeric.your row data is $row")
        false
      } else if (idFlag && policy.isDefined && isVidStringType) {
        printChoice(
          streamFlag,
          s"only int vidType can use policy, but your vidType is FIXED_STRING.your row data is $row")
        false
      } else true
    idFlag && policyFlag
  }

  /**
    * convert row data to {@link Edge}
    */
  def convertToEdge(row: Row,
                    edgeConfig: EdgeConfigEntry,
                    isVidStringType: Boolean,
                    fieldKeys: List[String],
                    fieldTypeMap: Map[String, Int]): Edge = {
    val sourceField = processField(edgeConfig.sourceField,
                                   "source_field",
                                   row,
                                   edgeConfig.sourcePolicy,
                                   isVidStringType)

    val targetField = processField(edgeConfig.targetField,
                                   "target_field",
                                   row,
                                   edgeConfig.targetPolicy,
                                   isVidStringType)

    val values = for {
      property <- fieldKeys if property.trim.length != 0
    } yield extraValueForClient(row, property, fieldTypeMap)

    if (edgeConfig.rankingField.isDefined) {
      val index   = row.schema.fieldIndex(edgeConfig.rankingField.get)
      val ranking = row.get(index).toString
      Edge(sourceField, targetField, Some(ranking.toLong), values)
    } else {
      Edge(sourceField, targetField, None, values)
    }
  }

  /**
    * process edge source and target field
    */
  def processField(field: String,
                   fieldType: String,
                   row: Row,
                   policy: Option[KeyPolicy.Value],
                   isVidStringType: Boolean): String = {
    var fieldValue = if (edgeConfig.isGeo && "source_field".equals(fieldType)) {
      val lat = row.getDouble(row.schema.fieldIndex(edgeConfig.latitude.get))
      val lng = row.getDouble(row.schema.fieldIndex(edgeConfig.longitude.get))
      indexCells(lat, lng).mkString(",")
    } else {
      val index = row.schema.fieldIndex(field)
      val value = row.get(index).toString
      if (value.equals(DEFAULT_EMPTY_VALUE)) "" else value
    }
    // process string type vid
    if (policy.isEmpty && isVidStringType) {
      fieldValue = NebulaUtils.escapeUtil(fieldValue).mkString("\"", "", "\"")
    }
    fieldValue
  }

  /**
    * encode edge
    */
  def encodeEdge(row: Row,
                 partitionNum: Int,
                 vidType: VidType.Value,
                 spaceVidLen: Int,
                 edgeItem: EdgeItem,
                 fieldTypeMap: Map[String, Int]): (Array[Byte], Array[Byte], Array[Byte]) = {
    isEdgeValid(row, edgeConfig, false, vidType == VidType.STRING)

    val srcIndex: Int = row.schema.fieldIndex(edgeConfig.sourceField)
    var srcId: String = row.get(srcIndex).toString
    if (srcId.equals(DEFAULT_EMPTY_VALUE)) {
      srcId = ""
    }

    val dstIndex: Int = row.schema.fieldIndex(edgeConfig.targetField)
    var dstId: String = row.get(dstIndex).toString
    if (dstId.equals(DEFAULT_EMPTY_VALUE)) {
      dstId = ""
    }

    if (edgeConfig.sourcePolicy.isDefined) {
      edgeConfig.sourcePolicy.get match {
        case KeyPolicy.HASH =>
          srcId = MurmurHash2
            .hash64(srcId.getBytes(), srcId.getBytes().length, 0xc70f6907)
            .toString
        case KeyPolicy.UUID =>
          throw new UnsupportedOperationException("do not support uuid yet")
        case _ =>
          throw new IllegalArgumentException(s"policy ${edgeConfig.sourcePolicy.get} is invalidate")
      }
    }
    if (edgeConfig.targetPolicy.isDefined) {
      edgeConfig.targetPolicy.get match {
        case KeyPolicy.HASH =>
          dstId = MurmurHash2
            .hash64(dstId.getBytes(), dstId.getBytes().length, 0xc70f6907)
            .toString
        case KeyPolicy.UUID =>
          throw new UnsupportedOperationException("do not support uuid yet")
        case _ =>
          throw new IllegalArgumentException(s"policy ${edgeConfig.targetPolicy.get} is invalidate")
      }
    }

    val ranking: Long = if (edgeConfig.rankingField.isDefined) {
      val rankIndex = row.schema.fieldIndex(edgeConfig.rankingField.get)
      row.get(rankIndex).toString.toLong
    } else {
      0
    }

    val srcPartitionId = NebulaUtils.getPartitionId(srcId, partitionNum, vidType)
    val dstPartitionId = NebulaUtils.getPartitionId(dstId, partitionNum, vidType)
    val codec          = new NebulaCodecImpl()

    import java.nio.ByteBuffer
    val srcBytes = if (vidType == VidType.INT) {
      ByteBuffer
        .allocate(8)
        .order(ByteOrder.nativeOrder)
        .putLong(srcId.toLong)
        .array
    } else {
      srcId.getBytes()
    }

    val dstBytes = if (vidType == VidType.INT) {
      ByteBuffer
        .allocate(8)
        .order(ByteOrder.nativeOrder)
        .putLong(dstId.toLong)
        .array
    } else {
      dstId.getBytes()
    }
    val positiveEdgeKey = codec.edgeKeyByDefaultVer(spaceVidLen,
                                                    srcPartitionId,
                                                    srcBytes,
                                                    edgeItem.getEdge_type,
                                                    ranking,
                                                    dstBytes)
    val reverseEdgeKey = codec.edgeKeyByDefaultVer(spaceVidLen,
                                                   dstPartitionId,
                                                   dstBytes,
                                                   -edgeItem.getEdge_type,
                                                   ranking,
                                                   srcBytes)

    val values = for {
      property <- fieldKeys if property.trim.length != 0
    } yield
      extraValueForSST(row, property, fieldTypeMap)
        .asInstanceOf[AnyRef]

    val edgeValue = codec.encodeEdge(edgeItem, nebulaKeys.asJava, values.asJava)
    (positiveEdgeKey, reverseEdgeKey, edgeValue)
  }
}
