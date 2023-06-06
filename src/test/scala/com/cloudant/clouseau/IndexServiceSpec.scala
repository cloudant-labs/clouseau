// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.clouseau

import org.apache.commons.configuration.SystemConfiguration
import scalang.Node
import org.apache.lucene.document._
import org.apache.lucene.search.{ FieldDoc, ScoreDoc }
import org.specs2.mutable.SpecificationWithJUnit
import org.apache.lucene.util.BytesRef
import org.apache.lucene.facet.FacetsConfig
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField
import scalang.Pid
import scala.Some
import java.io.File
import scala.collection.JavaConversions._

class IndexServiceSpec extends SpecificationWithJUnit {
  sequential

  "an index" should {

    "not be closed if close_if_idle and idle_check_interval_secs not set" in new index_service {
      indexNotClosedAfterTimeout(node, service)
    }

    "not be closed if idle_check_interval_secs set and close_if_idle set to false" in new index_service_with_idle_timeout_and_close_if_idle_false {
      indexNotClosedAfterTimeout(node, service)
    }

    "not be closed if close_if_idle set to false" in new index_service_with_idle_timeout_only {
      indexNotClosedAfterTimeout(node, service)
    }

    "be closed after idle timeout" in new index_service_with_idle_timeout_and_close_if_idle {
      indexClosedAfterTimeOut(node, service)
    }

    "not be closed if there is any activity before two consecutive idle checks" in new index_service_with_idle_timeout_and_close_if_idle {
      indexNotClosedAfterActivityBetweenTwoIdleChecks(node, service)
    }

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

    "support highlighting" in new index_service {
      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      doc1.add(new StringField("field1", "bar", Field.Store.YES))
      doc1.add(new StringField("field2", "bar", Field.Store.NO))
      doc1.add(new StringField("field3", "bar", Field.Store.YES))
      doc1.add(new StringField("field3", "bar", Field.Store.YES))
      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok

      // Basic
      (node.call(service, SearchRequest(options =
        Map('highlight_fields -> List("field1"), 'query -> "field1:bar")))
        must beLike {
          case ('ok, List(_, ('total_hits, 1),
            ('hits, List(
              Hit(_, List(("_id", "foo"), ("field1", "bar"), ("field3", List("bar", "bar")),
                ("_highlights", List(("field1", List("<em>bar</em>"))))))
              )))) => ok
        })

      // Attempted highlight on non-stored field
      (node.call(service, SearchRequest(options =
        Map('highlight_fields -> List("field1", "field2"), 'query -> "field1:bar")))
        must beLike {
          case ('ok, List(_, ('total_hits, 1),
            ('hits, List(
              Hit(_, List(("_id", "foo"), ("field1", "bar"), ("field3", List("bar", "bar")),
                ("_highlights", List(("field1", List("<em>bar</em>")),
                  ("field2", Nil)))))
              )))) => ok
        })

      // highlights on duplicated field
      (node.call(service, SearchRequest(options =
        Map('highlight_fields -> List("field3"), 'query -> "field3:bar")))
        must beLike {
          case ('ok, List(_, ('total_hits, 1),
            ('hits, List(
              Hit(_, List(("_id", "foo"), ("field1", "bar"), ("field3", List("bar", "bar")),
                ("_highlights", List(("field3", List("<em>bar</em>", "<em>bar</em>"))))))
              )))) => ok
        })
    }

    "when limit=0 return only the number of hits" in new index_service {
      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      val doc2 = new Document()
      doc2.add(new StringField("_id", "bar", Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok
      node.call(service, SearchRequest(options =
        Map('limit -> 0))) must beLike {
        case ('ok, List(_, ('total_hits, 2),
          ('hits, List()))) => ok
      }
    }

    "support include_fields" in new index_service {
      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      doc1.add(new StringField("field1", "f11", Field.Store.YES))
      doc1.add(new StringField("field2", "f21", Field.Store.YES))
      doc1.add(new StringField("field3", "f31", Field.Store.YES))
      val doc2 = new Document()
      doc2.add(new StringField("_id", "bar", Field.Store.YES))
      doc2.add(new StringField("field1", "f12", Field.Store.YES))
      doc2.add(new StringField("field2", "f22", Field.Store.YES))
      doc2.add(new StringField("field3", "f32", Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok

      //Include only field1
      (node.call(service, SearchRequest(options =
        Map('include_fields -> List("field1"))))
        must beLike {
          case ('ok, List(_, ('total_hits, 2),
            ('hits, List(
              Hit(_, List(("_id", "foo"), ("field1", "f11"))),
              Hit(_, List(("_id", "bar"), ("field1", "f12")))
              )))) => ok
        })

      //Include only field1 and field2
      (node.call(service, SearchRequest(options =
        Map('include_fields -> List("field1", "field2"))))
        must beLike {
          case ('ok, List(_, ('total_hits, 2),
            ('hits, List(
              Hit(_, List(("_id", "foo"), ("field1", "f11"), ("field2", "f21"))),
              Hit(_, List(("_id", "bar"), ("field1", "f12"), ("field2", "f22")))
              )))) => ok
        })

      //Include no field
      (node.call(service, SearchRequest(options =
        Map('include_fields -> List())))
        must beLike {
          case ('ok, List(_, ('total_hits, 2),
            ('hits, List(
              Hit(_, List(("_id", "foo"))),
              Hit(_, List(("_id", "bar")))
              )))) => ok
        })
    }

    "support faceting and drilldown" in new index_service {
      val facetsConfig = new FacetsConfig()

      val doc1 = facetsConfig.build({
        val d = new Document()
        d.add(new StringField("_id", "foo", Field.Store.YES))
        d.add(new SortedSetDocValuesFacetField("ffield", "f1"))
        d
      })

      val doc2 = facetsConfig.build({
        val d = new Document()
        d.add(new StringField("_id", "foo2", Field.Store.YES))
        d.add(new SortedSetDocValuesFacetField("ffield", "f1"))
        d
      })

      val doc3 = facetsConfig.build({
        val d = new Document()
        d.add(new StringField("_id", "foo3", Field.Store.YES))
        d.add(new SortedSetDocValuesFacetField("ffield", "f3"))
        d
      })

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("foo2", doc2)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("foo3", doc3)) must be equalTo 'ok

      //counts
      (node.call(service, SearchRequest(options =
        Map('counts -> List("ffield"))))
        must beLike {
          case ('ok, List(_, ('total_hits, 3), _,
            ('counts, List((
              List("ffield"), 0.0, List(
                (List("ffield", "f1"), 2.0, List()),
                (List("ffield", "f3"), 1.0, List()))
              ))))) => ok
        })

      //drilldown - one value
      (node.call(service, SearchRequest(options =
        Map('counts -> List("ffield"), 'drilldown -> List(List("ffield", "f1")))))
        must beLike {
          case ('ok, List(_, ('total_hits, 2), _,
            ('counts, List((
              List("ffield"), 0.0, List(
                (List("ffield", "f1"), 2.0, List()))
              ))))) => ok
        })

      //drilldown - multivalued
      (node.call(service, SearchRequest(options =
        Map('counts -> List("ffield"), 'drilldown -> List(List("ffield", "f1", "f3")))))
        must beLike {
          case ('ok, List(_, ('total_hits, 3), _,
            ('counts, List((
              List("ffield"), 0.0, List(
                (List("ffield", "f1"), 2.0, List()),
                (List("ffield", "f3"), 1.0, List()))
              ))))) => ok
        })
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

    "support sort by distance in group search" in new index_service {
      val foo = new BytesRef("foo")
      val bar = new BytesRef("bar")
      val zzz = new BytesRef("zzz")

      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      doc1.add(new DoubleField("lon", 0.5, Field.Store.YES))
      doc1.add(new DoubleField("lat", 57.15, Field.Store.YES))

      val doc2 = new Document()
      doc2.add(new StringField("_id", "bar", Field.Store.YES))
      doc1.add(new DoubleField("lon", 10, Field.Store.YES))
      doc1.add(new DoubleField("lat", 57.15, Field.Store.YES))

      val doc3 = new Document()
      doc3.add(new StringField("_id", "zzz", Field.Store.YES))
      doc3.add(new DoubleField("lon", 3, Field.Store.YES))
      doc3.add(new DoubleField("lat", 57.15, Field.Store.YES))

      node.call(service, UpdateDocMsg("foo", doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("bar", doc2)) must be equalTo 'ok
      node.call(service, UpdateDocMsg("zzz", doc3)) must be equalTo 'ok

      node.call(service, Group1Msg("*:*", "_id", true, "<distance,lon,lat,0.2,57.15,km>", 0, 10)) must beLike {
        case ('ok, List((foo, _), (zzz, _), (bar, _))) => ok
      }

      node.call(service, Group1Msg("*:*", "_id", true, "<distance,lon,lat,12,57.15,km>", 0, 10)) must beLike {
        case ('ok, List((bar, _), (zzz, _), (foo, _))) => ok
      }
    }

    "supports partitioned databases" in new index_service {
      val doc1 = new Document()
      val id1 = "foo:hello"
      doc1.add(new StringField("_id", id1, Field.Store.YES))
      doc1.add(new StringField("field", "fieldvalue", Field.Store.YES))
      doc1.add(new StringField("_partition", "foo", Field.Store.YES))

      val doc2 = new Document()
      val id2 = "bar:world"
      doc2.add(new StringField("_id", id2, Field.Store.YES))
      doc2.add(new StringField("field", "fieldvalue", Field.Store.YES))
      doc2.add(new StringField("_partition", "bar", Field.Store.YES))

      node.call(service, UpdateDocMsg(id1, doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg(id2, doc2)) must be equalTo 'ok

      val req = SearchRequest(
        options = Map(
          'query -> "field:fieldvalue",
          'partition -> "foo"
        )
      )

      (node.call(service, req)
        must beLike {
          case ('ok, (List(_, ('total_hits, 1), _))) => ok
        })
    }

    "ignores partitioned key if partition missing" in new index_service {
      val doc1 = new Document()
      val id1 = "foo:hello"
      doc1.add(new StringField("_id", id1, Field.Store.YES))
      doc1.add(new StringField("field", "fieldvalue", Field.Store.YES))
      doc1.add(new StringField("_partition", "foo", Field.Store.YES))

      val doc2 = new Document()
      val id2 = "bar:world"
      doc2.add(new StringField("_id", id2, Field.Store.YES))
      doc2.add(new StringField("field", "fieldvalue", Field.Store.YES))
      doc2.add(new StringField("_partition", "bar", Field.Store.YES))

      node.call(service, UpdateDocMsg(id1, doc1)) must be equalTo 'ok
      node.call(service, UpdateDocMsg(id2, doc2)) must be equalTo 'ok

      val req = SearchRequest(
        options = Map(
          'query -> "field:fieldvalue"
        )
      )

      (node.call(service, req)
        must beLike {
          case ('ok, (List(_, ('total_hits, 2), _))) => ok
        })
    }

    "can make a snapshot" in new index_service {
      val doc1 = new Document()
      val id1 = "foo:hello"
      doc1.add(new StringField("_id", id1, Field.Store.YES))
      node.call(service, UpdateDocMsg(id1, doc1)) must be equalTo 'ok
      node.call(service, SetUpdateSeqMsg(10)) must be equalTo 'ok
      node.send(service, 'maybe_commit)
      Thread.sleep(1000)
      val snapshotDir = new File(new File("target", "indexes"), System.currentTimeMillis().toString)
      snapshotDir.exists must beFalse
      node.call(service, ('create_snapshot, snapshotDir.getAbsolutePath)) must be equalTo 'ok
      snapshotDir.exists must beTrue
      snapshotDir.list.sorted must be equalTo Array("_0.cfe", "_0.cfs", "_0.si", "segments_1")
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

  private def indexNotClosedAfterTimeout(node: Node, service: Pid) {
    val value, query = "foo"
    val doc = new Document()
    doc.add(new StringField("_id", value, Field.Store.YES))
    doc.add(new NumericDocValuesField("timestamp", System.currentTimeMillis()))

    node.call(service, UpdateDocMsg(value, doc)) must be equalTo 'ok
    val req = SearchRequest(options = Map('query -> "_id:%s".format(query)))
    (node.call(service, req)
      must beLike {
        case ('ok, (List(_, ('total_hits, 1), _))) => ok
      })
    Thread.sleep(4200)
    (node.isAlive(service) must beTrue)
  }

  private def indexClosedAfterTimeOut(node: Node, service: Pid) {
    val value, query = "foo"
    val doc = new Document()
    doc.add(new StringField("_id", value, Field.Store.YES))
    doc.add(new NumericDocValuesField("timestamp", System.currentTimeMillis()))

    node.call(service, UpdateDocMsg(value, doc)) must be equalTo 'ok
    val req = SearchRequest(options = Map('query -> "_id:%s".format(query)))
    (node.call(service, req)
      must beLike {
        case ('ok, (List(_, ('total_hits, 1), _))) => ok
      })
    Thread.sleep(4200)
    (node.isAlive(service) must beFalse)
  }

  private def indexNotClosedAfterActivityBetweenTwoIdleChecks(node: Node,
                                                              service: Pid) {
    var value, query = "foo"
    var doc = new Document()
    doc.add(new StringField("_id", value, Field.Store.YES))
    doc.add(new NumericDocValuesField("timestamp", System.currentTimeMillis()))

    node.call(service, UpdateDocMsg(value, doc)) must be equalTo 'ok
    val req = SearchRequest(options = Map('query -> "_id:%s".format(query)))
    (node.call(service, req)
      must beLike {
        case ('ok, (List(_, ('total_hits, 1), _))) => ok
      })

    Thread.sleep(3000)
    value = "foo2"
    query = "foo2"
    doc = new Document()
    doc.add(new StringField("_id", value, Field.Store.YES))
    doc.add(new NumericDocValuesField("timestamp", System.currentTimeMillis()))
    node.call(service, UpdateDocMsg(value, doc)) must be equalTo 'ok

    Thread.sleep(2000)
    (node.isAlive(service) must beTrue)

    Thread.sleep(1200)
    (node.isAlive(service) must beFalse)
  }

}

trait index_service extends RunningNode {
  val config = new SystemConfiguration()
  val args = new ConfigurationArgs(config)
  var service: Pid = null
  val path = System.currentTimeMillis().toString

  override def before {
    val dir = new File(new File("target", "indexes"), path)
    if (dir.exists) {
      for (f <- dir.listFiles) {
        f.delete
      }
    }

    val (_, pid: Pid) = IndexService.start(node, config, path, options())
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

trait index_service_with_idle_timeout_and_close_if_idle extends index_service {
  override val config = new SystemConfiguration()
  config.addProperty("clouseau.close_if_idle", true)
  config.addProperty("clouseau.idle_check_interval_secs", 2)
  override val args = new ConfigurationArgs(config)
}

trait index_service_with_idle_timeout_only extends index_service {
  override val config = new SystemConfiguration()
  config.addProperty("clouseau.idle_check_interval_secs", 2)
  override val args = new ConfigurationArgs(config)
}

trait index_service_with_idle_timeout_and_close_if_idle_false extends index_service {
  override val config = new SystemConfiguration()
  config.addProperty("clouseau.close_if_idle", false)
  config.addProperty("clouseau.idle_check_interval_secs", 2)
  override val args = new ConfigurationArgs(config)
}
