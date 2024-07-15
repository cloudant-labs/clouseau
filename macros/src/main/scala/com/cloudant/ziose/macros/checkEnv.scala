package com.cloudant.ziose.macros

import scala.language.experimental.macros
import scala.reflect.macros.whitebox
import scala.annotation.{StaticAnnotation, compileTimeOnly}

@compileTimeOnly("Enable macro paradise to expand macro annotations")
class checkEnv(key: String) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro checkEnv.impl
}

object checkEnv {
  def impl(c: whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._

    def extractAnnotationArg(apply: Tree): String = {
      val q"""new $x($arg).$y(..$args2)""" = apply
      arg match {
        case Literal(Constant(x: String)) => x
        case _                            => System.getProperty("env")
      }
    }

    val arg = extractAnnotationArg(c.macroApplication)

    annottees.map(_.tree).toList match {
      case q"$mods def toStringMacro[..$tpes](...$args): $returnType = { ..$body }" :: Nil =>
        if (arg == "dev") {
          c.Expr(
            q"""
                override def toString[..$tpes](...$args): String = {
                  val head = (..$body).head
                  val tail = (..$body).tail
                  s"$$head(\n  $${tail.mkString(",\n  ")}\n)"
                }
            """
          )
        } else {
          c.Expr(
            q"""
                override def toString[..$tpes](...$args): String = {
                  val head = (..$body).head
                  val tail = (..$body).tail
                  s"$$head($${tail.mkString(", ")})"
                }
            """
          )
        }
      case _ => c.abort(c.enclosingPosition, "Invalid annottee")
    }
  }
}
