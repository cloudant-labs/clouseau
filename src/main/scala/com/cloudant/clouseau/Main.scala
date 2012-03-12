package com.cloudant.clouseau

import java.io.File
import scalang.{ Service, ServiceContext, Reference, Pid, Node, NoArgs }

object Main {

  type OptionMap = Map[Symbol, String]
  def nextOption(parsedArguments: OptionMap, remainingArguments: List[String]): OptionMap = {
    def isOption(s: String) = s.startsWith("--")

    remainingArguments match {
      case Nil => parsedArguments
      case "--name" :: value :: tail =>
        nextOption(parsedArguments ++ Map('name -> value), tail)
      case "--cookie" :: value :: tail =>
        nextOption(parsedArguments ++ Map('cookie -> value), tail)
      case "--dir" :: value :: tail =>
        nextOption(parsedArguments ++ Map('dir -> value), tail)
      case unknownOption :: tail =>
        println("Unknown option: " + unknownOption)
        sys.exit(0)
    }
  }

  def main(args: Array[String]) {
    val options = nextOption(Map(), args.toList)
    val name = options.getOrElse('name, "clouseau@127.0.0.1")
    val node = options.get('cookie) match {
      case None => Node(name)
      case (cookie: Option[String]) => Node(name, cookie.get)
    }
    val dir = options.getOrElse('dir, "target/indexes")

    node.spawnService[IndexManager, IndexManagerArgs]('main, IndexManagerArgs(new File(dir)))
    println("Clouseau running.")
  }

}