/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange.processor

import java.nio.{ByteBuffer, ByteOrder}

import com.vesoft.exchange.common.{ErrorHandler, GraphProvider, MetaProvider, VidType}
import com.vesoft.exchange.common.{KeyPolicy, Vertex, Vertices}
import com.vesoft.exchange.common.config.{
  Configs,
  FileBaseSinkConfigEntry,
  SinkCategory,
  TagConfigEntry
}
import com.vesoft.exchange.common.processor.Processor
import com.vesoft.exchange.common.utils.NebulaUtils
import com.vesoft.exchange.common.utils.NebulaUtils.DEFAULT_EMPTY_VALUE
import com.vesoft.exchange.common.writer.{GenerateSstFile, NebulaGraphClientWriter, NebulaSSTWriter}
import com.vesoft.exchange.common.VidType
import com.vesoft.nebula.encoder.NebulaCodecImpl
import com.vesoft.nebula.exchange.TooManyErrorsException
import com.vesoft.nebula.meta.TagItem
import org.apache.commons.codec.digest.MurmurHash2
import org.apache.log4j.Logger
import org.apache.spark.TaskContext
import org.apache.spark.sql.{DataFrame, Encoders, Row, SparkSession}
import org.apache.spark.util.LongAccumulator

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/**
  *
  * @param data
  * @param tagConfig
  * @param fieldKeys
  * @param nebulaKeys
  * @param config
  * @param batchSuccess
  * @param batchFailure
  */
class VerticesProcessor(spark: SparkSession,
                        data: DataFrame,
                        tagConfig: TagConfigEntry,
                        fieldKeys: List[String],
                        nebulaKeys: List[String],
                        config: Configs,
                        batchSuccess: LongAccumulator,
                        batchFailure: LongAccumulator)
    extends Processor {

  @transient
  private[this] lazy val LOG = Logger.getLogger(this.getClass)

  private def processEachPartition(iterator: Iterator[Vertex]): Unit = {
    val graphProvider =
      new GraphProvider(config.databaseConfig.getGraphAddress,
                        config.connectionConfig.timeout,
                        config.sslConfig)

    val writer = new NebulaGraphClientWriter(config.databaseConfig,
                                             config.userConfig,
                                             config.rateConfig,
                                             tagConfig,
                                             graphProvider)

    val errorBuffer = ArrayBuffer[String]()

    writer.prepare()
    // batch write tags
    val startTime = System.currentTimeMillis
    iterator.grouped(tagConfig.batch).foreach { vertex =>
      val vertices      = Vertices(nebulaKeys, vertex.toList, tagConfig.vertexPolicy)
      val failStatement = writer.writeVertices(vertices)
      if (failStatement == null) {
        batchSuccess.add(1)
      } else {
        errorBuffer.append(failStatement)
        batchFailure.add(1)
        if (batchFailure.value >= config.errorConfig.errorMaxSize) {
          throw TooManyErrorsException(
            s"There are too many failed batches, batch amount: ${batchFailure.value}, " +
              s"your config max error size: ${config.errorConfig.errorMaxSize}")
        }
      }
    }
    if (errorBuffer.nonEmpty) {
      ErrorHandler.save(
        errorBuffer,
        s"${config.errorConfig.errorPath}/${tagConfig.name}.${TaskContext.getPartitionId()}")
      errorBuffer.clear()
    }
    LOG.info(s"tag ${tagConfig.name} import in spark partition ${TaskContext
      .getPartitionId()} cost ${System.currentTimeMillis() - startTime} ms")
    writer.close()
    graphProvider.close()
  }

  override def process(): Unit = {

    val address = config.databaseConfig.getMetaAddress
    val space   = config.databaseConfig.space

    val timeout         = config.connectionConfig.timeout
    val retry           = config.connectionConfig.retry
    val metaProvider    = new MetaProvider(address, timeout, retry, config.sslConfig)
    val fieldTypeMap    = NebulaUtils.getDataSourceFieldType(tagConfig, space, metaProvider)
    val isVidStringType = metaProvider.getVidType(space) == VidType.STRING
    val partitionNum    = metaProvider.getPartNumber(space)

    if (tagConfig.dataSinkConfigEntry.category == SinkCategory.SST) {
      val fileBaseConfig = tagConfig.dataSinkConfigEntry.asInstanceOf[FileBaseSinkConfigEntry]
      val namenode       = fileBaseConfig.fsName.orNull
      val tagName        = tagConfig.name
      val vidType        = metaProvider.getVidType(space)

      val spaceVidLen = metaProvider.getSpaceVidLen(space)
      val tagItem     = metaProvider.getTagItem(space, tagName)
      val emptyValue  = ByteBuffer.allocate(0).array()

      var sstKeyValueData = data
        .dropDuplicates(tagConfig.vertexField)
        .mapPartitions { iter =>
          iter.map { row =>
            encodeVertex(row, partitionNum, vidType, spaceVidLen, tagItem, fieldTypeMap)
          }
        }(Encoders.tuple(Encoders.BINARY, Encoders.BINARY))

      // repartition dataframe according to nebula part, to make sure sst files for one part has no overlap
      if (tagConfig.repartitionWithNebula) {
        sstKeyValueData = customRepartition(spark, sstKeyValueData, partitionNum)
      }

      sstKeyValueData
        .toDF("key", "value")
        .sortWithinPartitions("key")
        .foreachPartition { iterator: Iterator[Row] =>
          val generateSstFile = new GenerateSstFile
          generateSstFile.writeSstFiles(iterator,
                                        fileBaseConfig,
                                        partitionNum,
                                        namenode,
                                        batchFailure)
        }
    } else {
      val streamFlag = data.isStreaming
      val vertices = data
        .filter { row =>
          isVertexValid(row, tagConfig, streamFlag, isVidStringType)
        }
        .map { row =>
          convertToVertex(row, tagConfig, isVidStringType, fieldKeys, fieldTypeMap)
        }(Encoders.kryo[Vertex])

      vertices.foreachPartition(processEachPartition _)
    }
  }

  /**
    * filter and check row data for vertex, if streaming only print log
    * for not streaming datasource, if the vertex data is invalid, throw AssertException.
    */
  def isVertexValid(row: Row,
                    tagConfig: TagConfigEntry,
                    streamFlag: Boolean,
                    isVidStringType: Boolean): Boolean = {
    val index = row.schema.fieldIndex(tagConfig.vertexField)
    if (index < 0 || row.isNullAt(index)) {
      printChoice(streamFlag, s"vertexId must exist and cannot be null, your row data is $row")
      return false
    }

    val vertexId = row.get(index).toString.trim
    // process int type vid
    if (tagConfig.vertexPolicy.isEmpty && !isVidStringType && !NebulaUtils.isNumic(vertexId)) {
      printChoice(
        streamFlag,
        s"space vidType is int, but your vertex id $vertexId is not numeric.your row data is $row")
      return false
    }
    // process string type vid
    if (tagConfig.vertexPolicy.isDefined && isVidStringType) {
      printChoice(
        streamFlag,
        s"only int vidType can use policy, but your vidType is FIXED_STRING.your row data is $row")
      return false
    }
    true
  }

  /**
    * Convert row data to {@link Vertex}
    */
  def convertToVertex(row: Row,
                      tagConfig: TagConfigEntry,
                      isVidStringType: Boolean,
                      fieldKeys: List[String],
                      fieldTypeMap: Map[String, Int]): Vertex = {
    val index    = row.schema.fieldIndex(tagConfig.vertexField)
    var vertexId = row.get(index).toString.trim
    if (vertexId.equals(DEFAULT_EMPTY_VALUE)) {
      vertexId = ""
    }

    if (tagConfig.vertexPolicy.isEmpty && isVidStringType) {
      vertexId = NebulaUtils.escapeUtil(vertexId).mkString("\"", "", "\"")
    }

    val values = for {
      property <- fieldKeys if property.trim.length != 0
    } yield extraValueForClient(row, property, fieldTypeMap)
    Vertex(vertexId, values)
  }

  /**
    * encode vertex
    */
  def encodeVertex(row: Row,
                   partitionNum: Int,
                   vidType: VidType.Value,
                   spaceVidLen: Int,
                   tagItem: TagItem,
                   fieldTypeMap: Map[String, Int]): (Array[Byte], Array[Byte]) = {
    // check if vertex id is valid, if not, throw AssertException
    isVertexValid(row, tagConfig, false, vidType == VidType.STRING)

    val index: Int       = row.schema.fieldIndex(tagConfig.vertexField)
    var vertexId: String = row.get(index).toString.trim
    if (vertexId.equals(DEFAULT_EMPTY_VALUE)) {
      vertexId = ""
    }
    if (tagConfig.vertexPolicy.isDefined) {
      tagConfig.vertexPolicy.get match {
        case KeyPolicy.HASH =>
          vertexId = MurmurHash2
            .hash64(vertexId.getBytes(), vertexId.getBytes().length, 0xc70f6907)
            .toString
        case KeyPolicy.UUID =>
          throw new UnsupportedOperationException("do not support uuid yet")
        case _ =>
          throw new IllegalArgumentException(s"policy ${tagConfig.vertexPolicy.get} is invalidate")
      }
    }

    val partitionId = NebulaUtils.getPartitionId(vertexId, partitionNum, vidType)

    import java.nio.ByteBuffer
    val vidBytes = if (vidType == VidType.INT) {
      ByteBuffer
        .allocate(8)
        .order(ByteOrder.nativeOrder)
        .putLong(vertexId.toLong)
        .array
    } else {
      vertexId.getBytes()
    }
    val codec     = new NebulaCodecImpl()
    val vertexKey = codec.vertexKey(spaceVidLen, partitionId, vidBytes, tagItem.getTag_id)
    val values = for {
      property <- fieldKeys if property.trim.length != 0
    } yield
      extraValueForSST(row, property, fieldTypeMap)
        .asInstanceOf[AnyRef]
    val vertexValue = codec.encodeTag(tagItem, nebulaKeys.asJava, values.asJava)
    (vertexKey, vertexValue)
  }
}
