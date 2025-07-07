package com.vesoft.exchange.common.utils

import scala.reflect.runtime.{universe => ru}
import scala.reflect.ClassTag
import scala.collection.mutable
/**
* utils for load companion
*/

object CompanionUtils {

  def lookupCompanion[T](name:String,cl:ClassLoader = null)(implicit ct: ClassTag[T]):T = {
    val currentClassLoader = {
      if(cl == null) Thread.currentThread().getContextClassLoader
      else cl
    }
    val mirror = ru.runtimeMirror(currentClassLoader)
    Class.forName(name,false,currentClassLoader)
    val moduleSymbol = mirror.staticModule(name)
    mirror.reflectModule(moduleSymbol).instance.asInstanceOf[T]
  }
}
