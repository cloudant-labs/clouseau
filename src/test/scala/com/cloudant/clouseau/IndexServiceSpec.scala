package com.cloudant.clouseau

import org.apache.commons.configuration.SystemConfiguration
import scalang.Node
import org.apache.lucene.document._
import org.apache.lucene.search.{ FieldDoc, ScoreDoc }
import org.specs2.mutable.SpecificationWithJUnit
import org.apache.lucene.util.BytesRef
import scalang.Pid
import scala.Some
import java.io.File

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
      (node.call(service, SearchRequest(options =
        Map('sort -> "_id<string>")))
        must beLike {
          case ('ok, List(_, ('total_hits, 2),
            ('hits, List(
              Hit(_, List(("_id", "bar"))),
              Hit(_, List(("_id", "foo")))
              )))) => ok
        })

      // Then t'other.
      (node.call(service, SearchRequest(options =
        Map('sort -> "-_id<string>")))
        must beLike {
          case ('ok, List(_, ('total_hits, 2),
            ('hits, List(
              Hit(_, List(("_id", "foo"))),
              Hit(_, List(("_id", "bar")))
              )))) => ok
        })

      // Can sort even if doc is missing that field
      (node.call(service, SearchRequest(options =
        Map('sort -> "foo<string>")))
        must beLike {
          case ('ok, List(_, ('total_hits, 2),
            ('hits, List(
              Hit(_, List(("_id", "foo"))),
              Hit(_, List(("_id", "bar")))
              )))) => ok
        })

    }

    "when limit=0 return only the number of hits" in new index_service {
      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      val doc2 = new Document()
      doc2.add(new StringField("field2", "test", Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok
      node.call(service, SearchRequest(options =
        Map('limit -> 0))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List()))) => ok
      }
    }

    "support bookmarks" in new index_service {
      val foo = new BytesRef("foo")
      val bar = new BytesRef("bar")

      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      val doc2 = new Document()
      doc2.add(new StringField("_id", "bar", Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok

      node.call(service, SearchRequest(options =
        Map('limit -> 1))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(1.0, 0), List(("_id", "foo"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'after -> (1.0, 0)))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(1.0, 1), List(("_id", "bar"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'sort -> "_id<string>"))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(_, 1), List(("_id", "bar"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'after -> List(new BytesRef("bar"), 1),
          'sort -> "_id<string>"))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(_, 0), List(("_id", "foo"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'after -> List(null, 0),
          'sort -> "nonexistent<string>"))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List('null, 1), List(("_id", "bar"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'sort -> List("<score>")))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(1.0, 0), List(("_id", "foo"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'sort -> List("<doc>")))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(0, 0), List(("_id", "foo"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'sort -> List("<score>", "_id<string>")))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(1.0, bar, 1), List(("_id", "bar"))))))) => ok
      }

      node.call(service, SearchRequest(options =
        Map('limit -> 1, 'sort -> List("<doc>", "_id<string>")))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List(Hit(List(0, foo, 0), List(("_id", "foo"))))))) => ok
      }

    }

    "support only group by string" in new index_service {
      val foo = new BytesRef("foo")
      val bar = new BytesRef("bar")

      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      doc1.add(new DoubleField("num", 1.0, Field.Store.YES))
      val doc2 = new Document()
      doc2.add(new StringField("_id", "bar", Field.Store.YES))
      doc1.add(new DoubleField("num", 2.0, Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok

      node.call(service, Group1Msg("_id:foo", "_id", true, "num", 0, 10)) must beLike {
        case ('ok, List((foo, List(2.0)))) => ok
      }

      node.call(service, Group1Msg("_id:foo", "_id<string>", true, "num", 0, 10)) must beLike {
        case ('ok, List((foo, List(2.0)))) => ok
      }

      node.call(service, Group1Msg("_id:foo", "num<number>", true, "num", 0, 10)) must beLike {
        case ('error, ('bad_request, "Group by number not supported. Group by string terms only.")) => ok
      }

      node.call(service, Group1Msg("_id:foo", "_id<number>", true, "num", 0, 10)) must beLike {
        case ('error, ('bad_request, "Group by number not supported. Group by string terms only.")) => ok
      }

    }

  }

  private def isSearchable(node: Node, service: Pid,
                           value: String, query: String) {
    val doc = new Document()
    doc.add(new StringField("_id", value, Field.Store.YES))
    doc.add(new NumericDocValuesField("timestamp", System.currentTimeMillis()))

    node.call(service, UpdateDocMsg(value, doc)) must be equalTo 'ok
    val req = SearchRequest(options = Map('query -> "_id:%s".format(query)))
    (node.call(service, req)
      must beLike {
        case ('ok, (List(_, ('total_hits, 1), _))) => ok
      })
  }

}

trait index_service extends RunningNode {
  val config = new SystemConfiguration()
  val args = new ConfigurationArgs(config)
  var service: Pid = null

  override def before {
    val dir = new File(new File("target", "indexes"), "bar")
    if (dir.exists) {
      for (f <- dir.listFiles) {
        f.delete
      }
    }

    val (_, pid: Pid) = IndexService.start(node, config, "bar", options())
    service = pid
  }

  def options(): Any = {
    "standard"
  }

  override def after {
    if (service != null) {
      node.send(service, 'delete)
    }
    super.after
  }

}

trait index_service_perfield extends index_service {

  override def options(): Any = {
    Map("name" -> "perfield", "default" -> "english")
  }

}
