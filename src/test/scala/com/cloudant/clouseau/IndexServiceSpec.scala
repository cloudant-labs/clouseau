package com.cloudant.clouseau

import org.apache.commons.configuration.BaseConfiguration
import scalang.Pid
import org.apache.lucene.document.{Document, StringField, Field}
import org.apache.lucene.search.ScoreDoc
import org.specs2.mutable.SpecificationWithJUnit

class IndexServiceSpec extends SpecificationWithJUnit {
  sequential

  "an index" should {

    "perform basic queries" in new index_service {
      val doc = new Document()
      doc.add(new StringField("_id", "foo", Field.Store.YES))
      node.call(service, UpdateDocMsg("foo", doc)) must be equalTo 'ok
      (node.call(service, SearchMsg("_id:foo", 1, refresh = true, None, 'relevance))
        must beLike {
        case ('ok, TopDocs(_, 1, _)) => ok
      })
    }

    "perform sorting" in new index_service {
      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      val doc2 = new Document()
      doc2.add(new StringField("_id", "bar", Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok

      // First one way.
      (node.call(service, SearchMsg("*:*", 2, refresh = true, None, "_id<string>"))
        must beLike {
        case ('ok, TopDocs(_, 2,
        List(
        Hit(_, List(("_id", "bar"))),
        Hit(_, List(("_id", "foo")))
        ))) => ok
      })

      // Then t'other.
      (node.call(service, SearchMsg("*:*", 2, refresh = true, None, "-_id<string>"))
        must beLike {
        case ('ok, TopDocs(_, 2,
        List(
        Hit(_, List(("_id", "foo"))),
        Hit(_, List(("_id", "bar")))
        ))) => ok
      })
    }

    "support bookmarks" in new index_service {
      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      val doc2 = new Document()
      doc2.add(new StringField("_id", "bar", Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok

      node.call(service, SearchMsg("*:*", 1, refresh = true, None, 'relevance)) must beLike {
        case ('ok, TopDocs(0, 2, List(Hit(List(1.0, 0), List((_id, foo)))))) => ok
      }

      node.call(service, SearchMsg("*:*", 1, refresh = true, Some(new ScoreDoc(0, 1.0f)), 'relevance)) must beLike {
        case ('ok, TopDocs(0, 2, List(Hit(List(1.0, 1), List((_id, bar)))))) => ok
      }
    }

  }

}

trait index_service extends RunningNode {
  val config = new BaseConfiguration()
  val args = new ConfigurationArgs(config)
  val (_, service: Pid) = IndexService.start(node, config, "bar", "standard")

  override def after {
    node.send(service, 'delete)
    super.after
  }

}