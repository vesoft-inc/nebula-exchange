/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common

import com.google.common.net.HostAndPort
import com.vesoft.exchange.common.config.{SslConfigEntry, SslType, Type}
import com.vesoft.nebula.PropertyType
import com.vesoft.nebula.client.graph.data.{
  CASignedSSLParam,
  HostAddress,
  SSLParam,
  SelfSignedSSLParam
}
import com.vesoft.nebula.client.meta.MetaClient
import com.vesoft.nebula.meta.{EdgeItem, TagItem}
import org.apache.log4j.Logger

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * MetaProvider provide nebula graph meta query operations.
  */
class MetaProvider(addresses: List[HostAndPort],
                   timeout: Int,
                   retry: Int,
                   sslConfigEntry: SslConfigEntry)
    extends AutoCloseable
    with Serializable {
  private[this] lazy val LOG = Logger.getLogger(this.getClass)

  val address: ListBuffer[HostAddress] = new ListBuffer[HostAddress]
  for (addr <- addresses) {
    address.append(new HostAddress(addr.getHost, addr.getPort))
  }

  private var metaClient: MetaClient = null
  var sslParam: SSLParam             = null
  // com.vesoft.exchange.common.config meta ssl
  if (sslConfigEntry.enableMeta) {
    if (sslConfigEntry.signType == SslType.CA) {
      val ca = sslConfigEntry.caSignParam
      sslParam = new CASignedSSLParam(ca.caCrtFilePath, ca.crtFilePath, ca.keyFilePath)
    } else {
      val self = sslConfigEntry.selfSignParam
      sslParam = new SelfSignedSSLParam(self.crtFilePath, self.keyFilePath, self.password)
    }
    metaClient = new MetaClient(address.asJava, timeout, retry, retry, true, sslParam)
  } else {
    metaClient = new MetaClient(address.asJava, timeout, retry, retry)
  }

  metaClient.connect()

  def getPartNumber(space: String): Int = {
    metaClient.getPartsAlloc(space).size()
  }

  def getVidType(space: String): VidType.Value = {
    val vidType = metaClient.getSpace(space).getProperties.getVid_type.getType
    if (vidType == PropertyType.FIXED_STRING) {
      return VidType.STRING
    }
    VidType.INT
  }

  def getTagSchema(space: String, tag: String): Map[String, Integer] = {
    val tagSchema = metaClient.getTag(space, tag)
    val schema    = new mutable.HashMap[String, Integer]

    val columns = tagSchema.getColumns
    for (colDef <- columns.asScala) {
      schema.put(new String(colDef.getName), colDef.getType.getType.getValue)
    }
    schema.toMap
  }

  def getEdgeSchema(space: String, edge: String): Map[String, Integer] = {
    val edgeSchema = metaClient.getEdge(space, edge)
    val schema     = new mutable.HashMap[String, Integer]

    val columns = edgeSchema.getColumns
    for (colDef <- columns.asScala) {
      schema.put(new String(colDef.getName), colDef.getType.getType.getValue)
    }
    schema.toMap
  }

  def getLabelType(space: String, label: String): Type.Value = {
    val tags = metaClient.getTags(space)
    for (tag <- tags.asScala) {
      if (new String(tag.getTag_name).equals(label)) {
        return Type.VERTEX
      }
    }
    val edges = metaClient.getEdges(space)
    for (edge <- edges.asScala) {
      if (new String(edge.getEdge_name).equals(label)) {
        return Type.EDGE
      }
    }
    null
  }

  def getSpaceVidLen(space: String): Int = {
    val spaceItem = metaClient.getSpace(space);
    if (spaceItem == null) {
      throw new IllegalArgumentException(s"space $space does not exist.")
    }
    spaceItem.getProperties.getVid_type.getType_length
  }

  def getTagItem(space: String, tag: String): TagItem = {
    val tagItemList = metaClient.getTags(space).asScala
    for (tagItem: TagItem <- tagItemList) {
      if (new String(tagItem.tag_name).equals(tag)) {
        return tagItem
      }
    }
    throw new IllegalArgumentException(s"tag ${space}.${tag} does not exist.")
  }

  def getEdgeItem(space: String, edge: String): EdgeItem = {
    val edgeItemList = metaClient.getEdges(space).asScala
    for (edgeItem: EdgeItem <- edgeItemList) {
      if (new String(edgeItem.edge_name).equals(edge)) {
        return edgeItem
      }
    }
    throw new IllegalArgumentException(s"edge ${space}.${edge} does not exist.")
  }

  override def close(): Unit = {
    metaClient.close()
  }

}

object VidType extends Enumeration {
  type Type = Value

  val STRING = Value("STRING")
  val INT    = Value("INT")
}
