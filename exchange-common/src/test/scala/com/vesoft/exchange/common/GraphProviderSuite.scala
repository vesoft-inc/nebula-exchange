/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common

import com.google.common.net.HostAndPort
import com.vesoft.exchange.common.config.{SslConfigEntry, SslType, UserConfigEntry}
import com.vesoft.nebula.client.graph.net.Session
import org.junit.{After, Before, Test}

class GraphProviderSuite {
  var graphProvider: GraphProvider = _
  var session: Session             = _
  val userConfig                   = UserConfigEntry("root", "nebula")

  @Before
  def setUp(): Unit = {
    val mockData = new NebulaGraphMock
    mockData.mockStringIdGraph()
    mockData.mockIntIdGraph()
    mockData.close()

    val sslConfig = SslConfigEntry(false, false, SslType.CA, null, null)
    graphProvider =
      new GraphProvider(List(HostAndPort.fromParts("127.0.0.1", 9669)), 5000, sslConfig)
  }

  @After
  def tearDown(): Unit = {
    graphProvider.close()
  }

  @Test
  def switchSpaceSuite(): Unit = {
    session = graphProvider.getGraphClient(userConfig)
    assert(graphProvider.switchSpace(session, "test_string").isSucceeded)
    assert(graphProvider.switchSpace(session, "test_int").isSucceeded)
    graphProvider.releaseGraphClient(session)
  }

  @Test
  def submitSuite(): Unit = {
    session = graphProvider.getGraphClient(userConfig)
    assert(graphProvider.submit(session, "show hosts").isSucceeded)
    graphProvider.releaseGraphClient(session)
  }

}
