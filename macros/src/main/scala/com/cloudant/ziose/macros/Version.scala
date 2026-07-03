package com.cloudant.ziose.macros

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

object Version {
  def getVersion : String = macro getVersionImpl

  def getVersionImpl(c : blackbox.Context) : c.Expr[String] = {
    import c.universe.{Literal, Constant}
    val settingsPrefix = "-Xmacro-settings:clouseau.version="
    c.compilerSettings.find(_.startsWith(settingsPrefix)).map(_.split('=')) match {
      case Some(Array(_, version)) =>
        c.Expr[String](Literal(Constant(version)))
      case _ =>
        c.Expr[String](Literal(Constant(s"<version not found - please set $settingsPrefix=version for scalac>")))
    }
  }
}
