package com.vesoft.exchange.common.utils

import scala.reflect.runtime.{universe => ru}
import scala.reflect.ClassTag
import scala.collection.mutable
/**
* utils for load companion
*/

object CompanionUtils {
  private[this] val cl = getClass.getClassLoader
  private[this] val mirror = ru.runtimeMirror(cl)

  def lookupCompanion[T](name:String)(implicit ct: ClassTag[T]):T = {
    Class.forName(name,false,cl)
    val moduleSymbol = mirror.staticModule(name)
    mirror.reflectModule(moduleSymbol).instance.asInstanceOf[T]
  }
}
