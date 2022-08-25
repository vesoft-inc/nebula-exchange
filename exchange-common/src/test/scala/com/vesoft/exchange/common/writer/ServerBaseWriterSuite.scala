/* Copyright (c) 2021 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.writer

import com.vesoft.exchange.common
import com.vesoft.exchange.common.{Edge, Edges, Vertex, Vertices}
import org.junit.Test

import scala.collection.mutable.ListBuffer

class ServerBaseWriterSuite extends ServerBaseWriter {

  @Test
  def toExecuteSentenceSuiteForVertex(): Unit = {
    val vertices: ListBuffer[Vertex] = new ListBuffer[Vertex]
    val tagName                      = "person"
    val propNames                    = List("name", "age", "gender", "high", "weight")

    val props1 = List("\"Tom\"", 10, 0, 172.5, 55)
    val props2 = List("\"Jena\"", 12, 1, 165.5, 45)
    vertices.append(Vertex("\"vid1\"", props1))
    vertices.append(Vertex("\"vid2\"", props2))
    val nebulaVertices = Vertices(propNames, vertices.toList)

    val sentence = toExecuteSentence(tagName, nebulaVertices)
    val expectSentence =
      "INSERT VERTEX `person`(`name`,`age`,`gender`,`high`,`weight`) VALUES " +
        "\"vid1\": (\"Tom\", 10, 0, 172.5, 55), " +
        "\"vid2\": (\"Jena\", 12, 1, 165.5, 45)"
    assert(sentence.equals(expectSentence))
  }

  @Test
  def toExecuteSentenceSuiteForVertexWithSymbol(): Unit = {
    val vertices: ListBuffer[Vertex] = new ListBuffer[Vertex]
    val tagName                      = "person,test_with^symbol#"
    val propNames                    = List("name_1", "age-1", "gender&1", "high%1", "weight,1")

    val props1 = List("\"Tom\"", 10, 0, 172.5, 55)
    val props2 = List("\"Jena\"", 12, 1, 165.5, 45)
    vertices.append(Vertex("\"vid_1\"", props1))
    vertices.append(Vertex("\"vid,2\"", props2))
    val nebulaVertices = Vertices(propNames, vertices.toList)

    val sentence = toExecuteSentence(tagName, nebulaVertices)
    val expectSentence =
      "INSERT VERTEX `person,test_with^symbol#`(`name_1`,`age-1`,`gender&1`,`high%1`,`weight,1`) VALUES " +
        "\"vid_1\": (\"Tom\", 10, 0, 172.5, 55), " +
        "\"vid,2\": (\"Jena\", 12, 1, 165.5, 45)"
    assert(sentence.equals(expectSentence))
  }

  @Test
  def toExecuteSentenceSuiteForEdge(): Unit = {
    val edges: ListBuffer[Edge] = new ListBuffer[Edge]
    val edgeType                = "friend"
    val propNames               = List("src_name", "dst_name", "time", "address", "relation")

    val props1 = List("\"Tom\"", "\"Jena\"", "2022-08-25", "hangzhou", "friend")
    val props2 = List("\"Jena\"", "\"Bob\"", "2022-08-25", "shanghai", "friend")
    edges.append(Edge("\"vid1\"", "\"vid2\"", Some(0L), props1))
    edges.append(Edge("\"vid2\"", "\"vid3\"", Some(1L), props2))
    val nebulaEdges = Edges(propNames, edges.toList)
    val sentence    = toExecuteSentence(edgeType, nebulaEdges)
    val expectSentence = "INSERT EDGE `friend`(`src_name`,`dst_name`,`time`,`address`,`relation`) VALUES" +
      " \"vid1\"->\"vid2\"@0: (\"Tom\", \"Jena\", 2022-08-25, hangzhou, friend), " +
      "\"vid2\"->\"vid3\"@1: (\"Jena\", \"Bob\", 2022-08-25, shanghai, friend)"
    assert(sentence.equals(expectSentence))
  }

  @Test
  def toExecuteSentenceSuiteForEdgeWithSymbol(): Unit = {
    val edges: ListBuffer[Edge] = new ListBuffer[Edge]
    val edgeType                = "friend"
    val propNames               = List("src_name", "dst_name", "time", "address", "relation")

    val props1 = List("\"Tom\"", "\"Jena\"", "2022-08-25", "hangzhou", "friend")
    val props2 = List("\"Jena\"", "\"Bob\"", "2022-08-25", "shanghai", "friend")
    edges.append(Edge("\"vid_1\"", "\"vid_2\"", Some(0L), props1))
    edges.append(Edge("\"vid_2,test-1\"", "\"vid&3^test*a\"", Some(1L), props2))
    val nebulaEdges = Edges(propNames, edges.toList)
    val sentence    = toExecuteSentence(edgeType, nebulaEdges)
    val expectSentence = "INSERT EDGE `friend`(`src_name`,`dst_name`,`time`,`address`,`relation`) VALUES " +
      "\"vid_1\"->\"vid_2\"@0: (\"Tom\", \"Jena\", 2022-08-25, hangzhou, friend), " +
      "\"vid_2,test-1\"->\"vid&3^test*a\"@1: (\"Jena\", \"Bob\", 2022-08-25, shanghai, friend)"
    assert(sentence.equals(expectSentence))
  }

  override def writeVertices(vertices: Vertices): String = ???

  override def writeEdges(edges: common.Edges): String = ???

  override def writeNgql(ngql: String): String = ???

  override def prepare(): Unit = ???

  override def close(): Unit = ???
}
