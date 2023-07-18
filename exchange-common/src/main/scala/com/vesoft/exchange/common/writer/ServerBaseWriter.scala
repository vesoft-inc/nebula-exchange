/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.writer

import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.RateLimiter
import com.vesoft.exchange.common.GraphProvider
import com.vesoft.exchange.common.{Edges, KeyPolicy, Vertices}
import com.vesoft.exchange.common.config.{DataBaseConfigEntry, RateConfigEntry, SchemaConfigEntry, TagConfigEntry, Type, UserConfigEntry, WriteMode}
import com.vesoft.nebula.ErrorCode
import org.apache.log4j.Logger

abstract class ServerBaseWriter extends Writer {
  private[this] val BATCH_INSERT_TEMPLATE = "INSERT %s `%s`(%s) VALUES %s"
  private[this] val BATCH_INSERT_IGNORE_INDEX_TEMPLATE =
    "INSERT %s IGNORE_EXISTED_INDEX `%s`(%s) VALUES %s"
  private[this] val INSERT_VALUE_TEMPLATE               = "%s: (%s)"
  private[this] val INSERT_VALUE_TEMPLATE_WITH_POLICY   = "%s(\"%s\"): (%s)"
  private[this] val ENDPOINT_TEMPLATE                   = "%s(\"%s\")"
  private[this] val EDGE_VALUE_WITHOUT_RANKING_TEMPLATE = "%s->%s: (%s)"
  private[this] val EDGE_VALUE_TEMPLATE                 = "%s->%s@%d: (%s)"

  private[this] val BATCH_DELETE_TEMPLATE = "DELETE %s %s"
  private[this] val BATCH_DELETE_WITH_EDGE_TEMPLATE = "DELETE %s %s WITH EDGE"
  private[this] val DELETE_VALUE_TEMPLATE               = "%s"

  def toExecuteSentence(name: String, vertices: Vertices, ignoreIndex: Boolean): String = {
    { if (ignoreIndex) BATCH_INSERT_IGNORE_INDEX_TEMPLATE else BATCH_INSERT_TEMPLATE }
      .format(
        Type.VERTEX.toString,
        name,
        vertices.propertyNames,
        vertices.values
          .map { vertex =>
            if (vertices.policy.isEmpty) {
              INSERT_VALUE_TEMPLATE.format(vertex.vertexID, vertex.propertyValues)
            } else {
              vertices.policy.get match {
                case KeyPolicy.HASH =>
                  INSERT_VALUE_TEMPLATE_WITH_POLICY
                    .format(KeyPolicy.HASH.toString, vertex.vertexID, vertex.propertyValues)
                case KeyPolicy.UUID =>
                  INSERT_VALUE_TEMPLATE_WITH_POLICY
                    .format(KeyPolicy.UUID.toString, vertex.vertexID, vertex.propertyValues)
                case _ =>
                  throw new IllegalArgumentException(
                    s"invalidate vertex policy ${vertices.policy.get}")
              }
            }
          }
          .mkString(", ")
      )
  }

  def toDeleteExecuteSentence(vertices: Vertices, deleteEdge: Boolean): String = {
    { if (deleteEdge) BATCH_DELETE_WITH_EDGE_TEMPLATE else BATCH_DELETE_TEMPLATE }
      .format(
        Type.VERTEX.toString,
        vertices.values
          .map { vertex =>
            if (vertices.policy.isEmpty) {
              DELETE_VALUE_TEMPLATE.format(vertex.vertexID)
            } else {
              vertices.policy.get match {
                case KeyPolicy.HASH =>
                  ENDPOINT_TEMPLATE
                    .format(KeyPolicy.HASH.toString, vertex.vertexID)
                case KeyPolicy.UUID =>
                  ENDPOINT_TEMPLATE
                    .format(KeyPolicy.UUID.toString, vertex.vertexID)
                case _ =>
                  throw new IllegalArgumentException(
                    s"invalidate vertex policy ${vertices.policy.get}")
              }
            }
          }
          .mkString(", ")
      )
  }

  def toExecuteSentence(name: String, edges: Edges, ignoreIndex: Boolean): String = {
    val values = edges.values
      .map { edge =>
        val source = edges.sourcePolicy match {
          case Some(KeyPolicy.HASH) =>
            ENDPOINT_TEMPLATE.format(KeyPolicy.HASH.toString, edge.source)
          case Some(KeyPolicy.UUID) =>
            ENDPOINT_TEMPLATE.format(KeyPolicy.UUID.toString, edge.source)
          case None =>
            edge.source
          case _ =>
            throw new IllegalArgumentException(
              s"invalidate source policy ${edges.sourcePolicy.get}")
        }

        val target = edges.targetPolicy match {
          case Some(KeyPolicy.HASH) =>
            ENDPOINT_TEMPLATE.format(KeyPolicy.HASH.toString, edge.destination)
          case Some(KeyPolicy.UUID) =>
            ENDPOINT_TEMPLATE.format(KeyPolicy.UUID.toString, edge.destination)
          case None =>
            edge.destination
          case _ =>
            throw new IllegalArgumentException(
              s"invalidate target policy ${edges.targetPolicy.get}")
        }

        if (edge.ranking.isEmpty)
          EDGE_VALUE_WITHOUT_RANKING_TEMPLATE
            .format(source, target, edge.propertyValues)
        else
          EDGE_VALUE_TEMPLATE.format(source, target, edge.ranking.get, edge.propertyValues)
      }
      .mkString(", ")

    (if (ignoreIndex) BATCH_INSERT_IGNORE_INDEX_TEMPLATE else BATCH_INSERT_TEMPLATE).format(
      Type.EDGE.toString,
      name,
      edges.propertyNames,
      values)
  }

  def writeVertices(vertices: Vertices, ignoreIndex: Boolean): String

  def writeEdges(edges: Edges, ignoreIndex: Boolean): String

  def writeNgql(ngql: String): String
}

/**
  * write data into Nebula Graph
  */
class NebulaGraphClientWriter(dataBaseConfigEntry: DataBaseConfigEntry,
                              userConfigEntry: UserConfigEntry,
                              rateConfig: RateConfigEntry,
                              config: SchemaConfigEntry,
                              graphProvider: GraphProvider)
    extends ServerBaseWriter {
  private val LOG = Logger.getLogger(this.getClass)

  require(
    dataBaseConfigEntry.getGraphAddress.nonEmpty
      && dataBaseConfigEntry.getMetaAddress.nonEmpty
      && dataBaseConfigEntry.space.trim.nonEmpty)
  require(userConfigEntry.user.trim.nonEmpty && userConfigEntry.password.trim.nonEmpty)

  val session     = graphProvider.getGraphClient(userConfigEntry)
  val rateLimiter = RateLimiter.create(rateConfig.limit)

  def prepare(): Unit = {
    val switchResult = graphProvider.switchSpace(session, dataBaseConfigEntry.space)
    if (!switchResult.isSucceeded) {
      this.close()
      throw new RuntimeException("Switch Failed for " + switchResult.getErrorMessage)
    }

    LOG.info(s"Connection to ${dataBaseConfigEntry.graphAddress}")
  }

  def execute(vertices: Vertices, writeMode: WriteMode.Mode): String = {
    val sentence = writeMode match {
      case WriteMode.INSERT =>
        toExecuteSentence(config.name, vertices, config.asInstanceOf[TagConfigEntry].ignoreIndex)
      case WriteMode.UPDATE =>
        toExecuteSentence(config.name, vertices, config.asInstanceOf[TagConfigEntry].ignoreIndex)
      case WriteMode.DELETE =>
        toDeleteExecuteSentence(vertices, config.asInstanceOf[TagConfigEntry].deleteEdge)
      case _ =>
        throw new IllegalArgumentException(s"write mode ${writeMode} not supported.")
    }
    sentence
  }

  override def writeVertices(vertices: Vertices, ignoreIndex: Boolean = false): String = {
    val sentence = toExecuteSentence(config.name, vertices, ignoreIndex)
    val statement = execute(vertices, config.asInstanceOf[TagConfigEntry].writeMode)
    if (rateLimiter.tryAcquire(rateConfig.timeout, TimeUnit.MILLISECONDS)) {
      val result = graphProvider.submit(session, sentence)
      if (result.isSucceeded) {
        LOG.info(
          s" write ${config.name}, batch size(${vertices.values.size}), latency(${result.getLatency})")
        return null
      }
      LOG.error(s"write vertex failed for ${result.getErrorMessage}")
      if (result.getErrorCode == ErrorCode.E_BAD_PERMISSION.getValue) {
        throw new RuntimeException(
          s"write ${config.name} failed for E_BAD_PERMISSION: ${result.getErrorMessage}")
      }
    } else {
      LOG.error(s"write vertex failed because write speed is too fast")
    }
    sentence
  }

  override def writeEdges(edges: Edges, ignoreIndex: Boolean = false): String = {
    val sentence = toExecuteSentence(config.name, edges, ignoreIndex)
    if (rateLimiter.tryAcquire(rateConfig.timeout, TimeUnit.MILLISECONDS)) {
      val result = graphProvider.submit(session, sentence)
      if (result.isSucceeded) {
        LOG.info(
          s" write ${config.name}, batch size(${edges.values.size}), latency(${result.getLatency}us)")
        return null
      }
      LOG.error(s"write edge failed for ${result.getErrorMessage}")
      if (result.getErrorCode == ErrorCode.E_BAD_PERMISSION.getValue) {
        throw new RuntimeException(
          s"write ${config.name} failed for E_BAD_PERMISSION: ${result.getErrorMessage}")
      }
    } else {
      LOG.error(s"write vertex failed because write speed is too fast")
    }
    sentence
  }

  override def writeNgql(ngql: String): String = {
    if (rateLimiter.tryAcquire(rateConfig.timeout, TimeUnit.MILLISECONDS)) {
      val result = graphProvider.submit(session, ngql)
      if (result.isSucceeded) {
        return null
      }
      LOG.error(s"reimport ngql failed for ${result.getErrorMessage}")
    } else {
      LOG.error(s"reimport ngql failed because write speed is too fast")
    }
    ngql
  }

  override def close(): Unit = {
    graphProvider.releaseGraphClient(session)
  }
}
