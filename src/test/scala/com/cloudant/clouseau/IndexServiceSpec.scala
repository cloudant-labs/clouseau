package com.cloudant.clouseau

import org.apache.commons.configuration.BaseConfiguration
import scalang.{ Node, Pid }
import org.apache.lucene.document.{ Document, StringField, Field }
import org.apache.lucene.search.{ FieldDoc, ScoreDoc }
import org.specs2.mutable.SpecificationWithJUnit
import org.apache.lucene.util.BytesRef

class IndexServiceSpec extends SpecificationWithJUnit {
  sequential

  "an index" should {

    "perform basic queries" in new index_service {
      isSearchable(node, service, "foo", "foo")
    }

    "be able to search uppercase _id" in new index_service {
      isSearchable(node, service, "FOO", "FOO")
    }

    "be able to search uppercase _id with prefix" in new index_service {
      isSearchable(node, service, "FOO", "FO*")
    }

    "be able to search uppercase _id with wildcards" in new index_service {
      isSearchable(node, service, "FOO", "F?O*")
    }

    "be able to search uppercase _id with range" in new index_service {
      isSearchable(node, service, "FOO", "[FOO TO FOO]")
    }

    "be able to search uppercase _id with regexp" in new index_service {
      isSearchable(node, service, "FOO", "/FOO/")
    }

    "be able to search uppercase _id with fuzzy" in new index_service {
      isSearchable(node, service, "FOO", "FO~")
    }

    "be able to search uppercase _id with perfield" in new index_service_perfield {
      isSearchable(node, service, "FOO", "FOO")
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

      // Can sort even if doc is missing that field
      (node.call(service, SearchMsg("*:*", 2, refresh = true, None, "foo<string>"))
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

      node.call(service, SearchMsg("*:*", 1, refresh = true, None, "_id<string>")) must beLike {
        case ('ok, TopDocs(0, 2, List(Hit(List(_, 1), List((_id, bar)))))) => ok
      }

      node.call(service, SearchMsg("*:*", 1, refresh = true, Some(new FieldDoc(1, 1.0f, Array(new BytesRef("bar")))),
        "_id<string>")) must beLike {
        case ('ok, TopDocs(0, 2, List(Hit(List(_, 0), List((_id, foo)))))) => ok
      }

      node.call(service, SearchMsg("*:*", 1, refresh = true, Some(new FieldDoc(0, 1.0f, Array(null))),
        "nonexistent<string>")) must beLike {
        case ('ok, TopDocs(0, 2, List(Hit(List('null, 1), List((_id, bar)))))) => ok
      }
    }

  }

  private def isSearchable(node: Node, service: Pid,
                           value: String, query: String) {
    val doc = new Document()
    doc.add(new StringField("_id", value, Field.Store.YES))
    node.call(service, UpdateDocMsg(value, doc)) must be equalTo 'ok
    (node.call(service, SearchMsg("_id:" + query, 1, refresh = true, None, 'relevance))
      must beLike {
        case ('ok, TopDocs(_, 1, _)) => ok
      })
  }

}

trait index_service extends RunningNode {
  val config = new BaseConfiguration()
  val args = new ConfigurationArgs(config)
  var (_, service: Pid) = IndexService.start(node, config, "bar", options())

  def options(): Any = {
    "standard"
  }

  override def after {
    node.send(service, 'delete)
    super.after
  }

}

trait index_service_perfield extends index_service {

  override def options(): Any = {
    Map("name" -> "perfield", "default" -> "english")
  }

}