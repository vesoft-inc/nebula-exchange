/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.writer

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Paths}

import com.vesoft.exchange.common.config.FileBaseSinkConfigEntry
import com.vesoft.exchange.common.utils.HDFSUtils
import org.apache.spark.TaskContext
import org.apache.spark.sql.Row
import org.apache.spark.util.LongAccumulator
import org.rocksdb.{EnvOptions, Options, RocksDB, SstFileWriter}
import org.slf4j.LoggerFactory

/**
  * NebulaSSTWriter
  */
class NebulaSSTWriter(path: String) extends Writer {
  var isOpen = false

  private val LOG = LoggerFactory.getLogger(getClass)

  try {
    RocksDB.loadLibrary()
    LOG.info("Loading RocksDB successfully")
  } catch {
    case _: Exception =>
      LOG.error("Can't load RocksDB library!")
  }

  // TODO More Config ...
  val options = new Options()
    .setCreateIfMissing(true)

  val env                   = new EnvOptions()
  var writer: SstFileWriter = _

  override def prepare(): Unit = {
    writer = new SstFileWriter(env, options)
    writer.open(path)
    isOpen = true
  }

  def write(key: Array[Byte], value: Array[Byte]): Unit = {
    writer.put(key, value)
  }

  override def close(): Unit = {
    if (isOpen) {
      writer.finish()
      writer.close()
    }
    options.close()
    env.close()
  }

}

class GenerateSstFile {
  private val LOG = LoggerFactory.getLogger(getClass)

  def writeSstFiles(iterator: Iterator[Row],
                    fileBaseConfig: FileBaseSinkConfigEntry,
                    partitionNum: Int,
                    namenode: String,
                    batchFailure: LongAccumulator): Unit = {
    val taskID                  = TaskContext.get().taskAttemptId()
    var writer: NebulaSSTWriter = null
    var currentPart             = -1
    val localPath               = fileBaseConfig.localPath
    val remotePath              = fileBaseConfig.remotePath
    try {
      iterator.foreach { vertex =>
        val key   = vertex.getAs[Array[Byte]](0)
        val value = vertex.getAs[Array[Byte]](1)
        var part = ByteBuffer
          .wrap(key, 0, 4)
          .order(ByteOrder.nativeOrder)
          .getInt >> 8
        if (part <= 0) {
          part = part + partitionNum
        }

        if (part != currentPart) {
          if (writer != null) {
            writer.close()
            val localFile = s"$localPath/$currentPart-$taskID.sst"
            HDFSUtils.upload(localFile,
                             s"$remotePath/${currentPart}/$currentPart-$taskID.sst",
                             namenode)
            Files.delete(Paths.get(localFile))
          }
          currentPart = part
          val tmp = s"$localPath/$currentPart-$taskID.sst"
          writer = new NebulaSSTWriter(tmp)
          writer.prepare()
        }
        writer.write(key, value)
      }
    } catch {
      case e: Throwable => {
        LOG.error("sst file write error,", e)
        batchFailure.add(1)
      }
    } finally {
      if (writer != null) {
        writer.close()
        val localFile = s"$localPath/$currentPart-$taskID.sst"
        HDFSUtils.upload(localFile,
                         s"$remotePath/${currentPart}/$currentPart-$taskID.sst",
                         namenode)
        Files.delete(Paths.get(localFile))
      }
    }
  }
}
