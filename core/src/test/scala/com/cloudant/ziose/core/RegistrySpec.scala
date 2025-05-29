package com.cloudant.ziose.core

import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.junit._

@RunWith(classOf[ZTestJUnitRunner])
class RegistrySpec extends JUnitRunnableSpec {
  val addSuite = suite("Add:")(
    test("canary test") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        index    <- registry.index
        size     <- registry.size
        keys     <- registry.list
      } yield assertTrue(index == 0, size == 0, keys.isEmpty)
    },
    test("add entry") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        _        <- registry.add(entry1)
        index    <- registry.index
        size     <- registry.size
        keys     <- registry.list
      } yield assertTrue(index == 1, size == 1, keys.toList == List(1))
    },
    test("add two different entries") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        _        <- registry.add(entry1) *> registry.add(entry2)
        index    <- registry.index
        size     <- registry.size
        keys     <- registry.list
      } yield assertTrue(index == 2, size == 2) &&
        assert(keys)(hasSameElementsDistinct(Set(1, 2)))
    },
    test("add two identical entries") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        entry    <- registry.add(entry1) *> registry.add(entry1) *> registry.get(entry1.id)
        index    <- registry.index
        size     <- registry.size
        keys     <- registry.list
      } yield assertTrue(index == 1, size == 1, entry.get.content == "entry1") &&
        assert(keys)(hasSameElements(Set(1)))
    },
    test("Add two entries with the same key") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        entry    <- registry.add(entryNew) *> registry.add(entry1) *> registry.get(entry1.id)
        index    <- registry.index
        size     <- registry.size
        keys     <- registry.list
      } yield assertTrue(index == 1, size == 1, entry.get.content == "entryNew") &&
        assert(keys)(hasSameElements(Set(1)))
    }
  )

  val buildWithSuite = suite("buildWith:")(
    test("buildWith") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        entry1   <- registry.buildWith(testBuildFn)
        index1   <- registry.index
        size1    <- registry.size
        entry2   <- registry.buildWith(testBuildFn)
        index2   <- registry.index
        size2    <- registry.size
        keys     <- registry.list
      } yield assertTrue(
        index1 == 1,
        size1 == 1,
        index2 == 2,
        size2 == 2,
        entry1.content == "0",
        entry2.content == "1"
      ) &&
        assert(keys)(hasSameElementsDistinct(Set(entry1.id, entry2.id)))
    }
  )

  val foreachSuite = suite("Foreach:")(
    test("foreach") {
      var sum = 0
      for {
        registry <- Registry.make[Int, String, TestEntry]
        _        <- registry.add(entry1) *> registry.add(entry2)
        _        <- registry.foreach(sum += _.id)
      } yield assertTrue(sum == 3)
    }
  )

  val getSuite = suite("Get:")(
    test("get entry") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        entry    <- registry.add(entry1) *> registry.get(entry1.id)
      } yield assertTrue(entry.get == entry1)
    },
    test("get entry but key does not exist") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        entry    <- registry.get(entry1.id)
      } yield assert(entry)(isNone)
    }
  )

  val indexSizeSuite = suite("Index and Size:")(
    test("index and size: add operation") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        _        <- registry.add(entry1)
        index    <- registry.index
        size     <- registry.size
      } yield assertTrue(index == 1, size == 1)
    },
    test("index and size: remove operation") {
      for {
        registry     <- Registry.make[Int, String, TestEntry]
        entryRemoved <- registry.add(entry1) *> registry.remove(entry1.id)
        index        <- registry.index
        size         <- registry.size
      } yield assertTrue(index == 1, size == 0, entryRemoved.get == entry1)
    },
    test("index and size: replace operation") {
      for {
        registry      <- Registry.make[Int, String, TestEntry]
        entryOriginal <- registry.add(entry1) *> registry.get(entry1.id)
        entryReplaced <- registry.replace(entryNew)
        entryNow      <- registry.get(entry1.id)
        index         <- registry.index
        size          <- registry.size
      } yield assertTrue(
        index == 1,
        size == 1,
        entryOriginal.get == entry1,
        entryOriginal == entryReplaced,
        entryOriginal != entryNow,
        entryNow.get.content == "entryNew"
      )
    },
    test("index and size: replace non-exist entry") {
      for {
        registry      <- Registry.make[Int, String, TestEntry]
        entryReplaced <- registry.replace(entry1)
        index         <- registry.index
        size          <- registry.size
      } yield assertTrue(index == 0, size == 0) && assert(entryReplaced)(isNone)
    }
  )

  val listSuite = suite("List:")(
    test("list entry keys") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        _        <- registry.add(entry1) *> registry.add(entry2)
        keys     <- registry.list
      } yield assert(keys)(hasSameElementsDistinct(Set(1, 2)))
    },
    test("list initial registry keys") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        keys     <- registry.list
      } yield assert(keys)(isEmpty)
    }
  )

  val mapSuite = suite("Map:")(
    test("map") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        _        <- registry.add(entry1) *> registry.add(entry2)
        result   <- registry.map(_.id * 2)
      } yield assert(result)(hasSameElementsDistinct(Set(2, 4)))
    }
  )

  val removeSuite = suite("Remove:")(
    test("remove entry") {
      for {
        registry     <- Registry.make[Int, String, TestEntry]
        entryRemoved <- registry.add(entry1) *> registry.remove(entry1.id)
        index        <- registry.index
        size         <- registry.size
        keys         <- registry.list
      } yield assertTrue(index == 1, size == 0, keys.isEmpty, entryRemoved.get == entry1)
    },
    test("remove entry but key does not exist") {
      for {
        registry <- Registry.make[Int, String, TestEntry]
        _        <- registry.remove(1)
        index    <- registry.index
        size     <- registry.size
        keys     <- registry.list
      } yield assertTrue(index == 0, size == 0, keys.isEmpty)
    }
  )

  val replaceSuite = suite("Replace:")(
    test("replace entry") {
      for {
        registry      <- Registry.make[Int, String, TestEntry]
        entryReplaced <- registry.add(entry1) *> registry.replace(entryNew)
        index         <- registry.index
        size          <- registry.size
        entryNow      <- registry.get(entry1.id)
      } yield assertTrue(
        index == 1,
        size == 1,
        entryReplaced.get == entry1,
        entryReplaced != entryNow,
        entryNow.get.content == "entryNew"
      )
    },
    test("replace entry but key does not exist") {
      for {
        registry      <- Registry.make[Int, String, TestEntry]
        entryReplaced <- registry.replace(entry1)
        index         <- registry.index
        size          <- registry.size
      } yield assertTrue(index == 0, size == 0) &&
        assert(entryReplaced)(isNone)
    }
  )

  def spec: Spec[Any with Scope, Throwable] = {
    suite("RegistrySpec")(
      addSuite,
      buildWithSuite,
      foreachSuite,
      getSuite,
      indexSizeSuite,
      listSuite,
      mapSuite,
      removeSuite,
      replaceSuite
    )
  }

  class TestEntry(val id: Int, val content: String) extends ForwardWithId[Int, String] {
    override def forward(a: String)(implicit trace: Trace): UIO[Boolean] = ???
    override def shutdown(implicit trace: Trace): UIO[Unit]              = ???
  }

  val entry1                                                          = new TestEntry(1, "entry1")
  val entry2                                                          = new TestEntry(2, "entry2")
  val entryNew                                                        = new TestEntry(1, "entryNew")
  def testBuildFn(id: Int): ZIO[Any with Scope, Throwable, TestEntry] = ZIO.succeed(new TestEntry(id, id.toString))
}
