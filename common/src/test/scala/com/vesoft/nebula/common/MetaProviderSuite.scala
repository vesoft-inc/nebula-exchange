/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.nebula.exchange

import com.google.common.net.HostAndPort
import com.vesoft.nebula.exchange.config.SslConfigEntry
import org.junit.{After, Before, Test}

import scala.com.vesoft.nebula.exchange.NebulaGraphMock

class MetaProviderSuite {

  var metaProvider: MetaProvider = _
  @Before
  def setUp(): Unit = {
    val mockData = new NebulaGraphMock
    mockData.mockStringIdGraph()
    mockData.mockIntIdGraph()
    mockData.close()

    val sslConfig = SslConfigEntry(false, false, SslType.CA, null, null)
    metaProvider =
      new MetaProvider(List(HostAndPort.fromParts("127.0.0.1", 9559)), 5000, 1, sslConfig)
  }

  @After
  def tearDown(): Unit = {
    if (metaProvider != null)
      metaProvider.close()
  }

  @Test
  def getPartNumberSuite(): Unit = {
    assert(metaProvider.getPartNumber("test_string") == 10)
    assert(metaProvider.getPartNumber("test_int") == 10)
  }

  @Test
  def getVidTypeSuite(): Unit = {
    assert(metaProvider.getVidType("test_string") == VidType.STRING)
    assert(metaProvider.getVidType("test_int") == VidType.INT)
  }

  @Test
  def getTagSchemaSuite(): Unit = {
    val tagSchema = metaProvider.getTagSchema("test_string", "person")
    assert(tagSchema.size == 14)
  }

  @Test
  def getEdgeSchemaSuite(): Unit = {
    val edgeSchema = metaProvider.getEdgeSchema("test_string", "friend")
    assert(edgeSchema.size == 14)
  }

  @Test
  def getLabelTypeSuite(): Unit = {
    assert(metaProvider.getLabelType("test_string", "person") == Type.VERTEX)
    assert(metaProvider.getLabelType("test_string", "friend") == Type.EDGE)
    assert(metaProvider.getLabelType("test_int", "person") == Type.VERTEX)
    assert(metaProvider.getLabelType("test_int", "friend") == Type.EDGE)
  }

  @Test
  def getSpaceVidLenSuite(): Unit = {
    assert(metaProvider.getSpaceVidLen("test_string") == 8)
    assert(metaProvider.getSpaceVidLen("test_int") == 8)
  }

  @Test
  def getTagItemSuite(): Unit = {
    val tagItem = metaProvider.getTagItem("test_string", "person")
    assert(new String(tagItem.tag_name).equals("person"))
  }

  @Test
  def getEdgeItemSuite(): Unit = {
    val edgeItem = metaProvider.getEdgeItem("test_string", "friend")
    assert(new String(edgeItem.edge_name).equals("friend"))
  }

}
