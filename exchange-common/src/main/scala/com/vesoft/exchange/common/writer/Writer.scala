/* Copyright (c) 2020 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.writer

/**
  *
  */
trait Writer extends Serializable {

  def prepare(): Unit

  def close()
}
