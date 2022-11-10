/* Copyright (c) 2022 vesoft inc. All rights reserved.
 *
 * This source code is licensed under Apache 2.0 License.
 */

package com.vesoft.exchange.common.utils

import org.junit.Test
import org.scalatest.Assertions.assertThrows

class SparkValidateSuite {

  @Test
  def validateSuite(): Unit = {
    SparkValidate.validate("2.2.0", "2.2.*")
    SparkValidate.validate("2.4.4", "2.4.*")
    SparkValidate.validate("3.0.0", "3.0.*", "3.1.*", "3.2.*", "3.3.*")
    assertThrows[RuntimeException](SparkValidate.validate("2.4.0", "2.2.*"))
    assertThrows[RuntimeException](SparkValidate.validate("2.2.0", "2.4.*"))
    assertThrows[RuntimeException](
      SparkValidate.validate("2.4.0", "3.0.*", "3.1.*", "3.2.*", "3.3.*"))
  }
}
