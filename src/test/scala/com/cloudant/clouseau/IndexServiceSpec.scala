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
import org.apache.lucene.facet.params.FacetIndexingParams
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetFields
import org.apache.lucene.facet.taxonomy.CategoryPath
import scalang.Pid
import scala.Some
import java.io.File
import scala.collection.JavaConverters._
import org.apache.lucene.queryparser.classic.ParseException

/*
mvn test -Dtest="com.cloudant.clouseau.IndexServiceSpec"
*/
class IndexServiceSpec extends SpecificationWithJUnit {
  sequential

  "a groups parser" should {
    def groups(value: Any): Map[Symbol, Any] = Map('groups -> value)

    "return None if option map doesn't contain 'groups'" in {
      IndexService.getGroups(Map().asInstanceOf[Map[Symbol, Any]]) must beEqualTo(None)
    }

    "return None if 'groups option contain 'nil" in {
      IndexService.getGroups(groups('nil)) must beEqualTo(None)
    }

    "return correct result for empty list groups" in {
      IndexService.getGroups(groups(List())) must beEqualTo(Some(List()))
    }

    "return correct result for groups in the expected format" in {
      var input = List(
        ("first", List(List())), ("second", List(Seq())), ("third", List("String")),
        ('null, List(List())), ('null, List(Seq())), ('null, List("String"))
      )
      IndexService.getGroups(groups(input)) must beEqualTo(Some(List(
        (Some("first"), List(List())), (Some("second"), List(Seq())), (Some("third"), List("String")),
        (None, List(List())), (None, List(Seq())), (None, List("String"))
      )))
    }

    "return error when groups is not a list" in {
      IndexService.getGroups(groups("not a List")) must throwA[ParseException].like {
        case e => e.getMessage must contain("invalid groups query")
      }
    }

  }

  "an include_fields parser" should {
    def includeFields(value: Any): Map[Symbol, Any] = Map('include_fields -> value)

    "return None if option map doesn't contain 'include_fields'" in {
      IndexService.getIncludeFields(Map().asInstanceOf[Map[Symbol, Any]]) must beEqualTo(None)
    }

    "return None if 'include_fields option contain 'nil" in {
      IndexService.getIncludeFields(includeFields('nil)) must beEqualTo(None)
    }

    "return correct result for empty list include_fields" in {
      IndexService.getIncludeFields(includeFields(List())) must beEqualTo(Some(Set("_id")))
    }

    "return correct result for List[String]" in {
      var planets = List("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")
      IndexService.getIncludeFields(includeFields(planets)) must beEqualTo(Some(
        Set("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "_id")
      ))
    }

    "return error when include_fields is not a list" in {
      IndexService.getIncludeFields(includeFields("not a List")) must throwA[ParseException].like {
        case e => e.getMessage must contain("not a List is not a valid 'include_fields query")
      }
    }

    "return error when one of the elements of include_fields is not a String" in {
      IndexService.getIncludeFields(includeFields(List("one", 2))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(one, 2) is not a valid 'include_fields query")
      }
    }
  }

  "a ranges parser" should {
    def ranges(value: Any): Map[Symbol, Any] = Map('ranges -> value)

    "return None if option map doesn't contain 'ranges'" in {
      IndexService.getRanges(Map().asInstanceOf[Map[Symbol, Any]]) must beEqualTo(None)
    }

    "return None if 'ranges option contain 'nil" in {
      IndexService.getRanges(ranges('nil)) must beEqualTo(None)
    }

    "return correct result for empty list ranges" in {
      IndexService.getRanges(ranges(List())) must beEqualTo(Some(List()))
    }

    "return correct result for empty list of queries" in {
      val input = List(("first", List()))
      IndexService.getRanges(ranges(input)) must beEqualTo(Some(input))
    }

    "return correct result when ranges are in correct format" in {
      val input = List(("first", List(("second", "third"))))
      IndexService.getRanges(ranges(input)) must beEqualTo(Some(input))
    }

    "return error when ranges is not a list" in {
      IndexService.getRanges(ranges("not a List")) must throwA[ParseException].like {
        case e => e.getMessage must contain("invalid ranges query")
      }
    }

    "return error when range name is not a String" in {
      IndexService.getRanges(ranges(List(1, List()))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(1, List()) is not a valid ranges query")
      }
    }

    "return error when query is not tuple with arity 2" in {
      IndexService.getRanges(ranges(List("first", List(("second", "third"), (1, 2, 3))))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(first, List((second,third), (1,2,3))) is not a valid ranges query")
      }
    }

    "return error when query label is not a String" in {
      IndexService.getRanges(ranges(List("first", List((1, "query"))))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(first, List((1,query))) is not a valid ranges query")
      }
    }

    "return error when query is not a list" in {
      IndexService.getRanges(ranges(List("first", List(("second", 1))))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(first, List((second,1))) is not a valid ranges query")
      }
    }

    "return error when queries is not a list" in {
      IndexService.getRanges(ranges(List("first", "not a List"))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(first, not a List) is not a valid ranges query")
      }
    }
  }

  "a drilldown parser" should {
    def drilldown(value: Any): Map[Symbol, Any] = Map('drilldown -> value)

    "return None if option map doesn't contain 'drilldown'" in {
      IndexService.getDrilldown(Map().asInstanceOf[Map[Symbol, Any]]) must beEqualTo(None)
    }

    "return None if 'drilldown option contain 'nil" in {
      IndexService.getDrilldown(drilldown('nil)) must beEqualTo(None)
    }

    "return correct result for single inner list of List[List[String]]" in {
      var planets = List(List("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"))
      IndexService.getDrilldown(drilldown(planets)) must beEqualTo(Some(planets))
    }

    "return correct result for multiple inner lists of List[List[String]]" in {
      var planets = List(List("Mercury", "Venus", "Earth"), List("Mars", "Jupiter", "Saturn"), List("Uranus", "Neptune"))
      IndexService.getDrilldown(drilldown(planets)) must beEqualTo(Some(planets))
    }

    "return error on List[NotAList]" in {
      IndexService.getDrilldown(drilldown(List(1, 2))) must throwA[ParseException].like {
        case e => e.getMessage must contain("invalid drilldown query List(1, 2)")
      }
    }

    "return error on List[MixOFListsAndNoneLists]" in {
      IndexService.getDrilldown(drilldown(List(List("1", "2"), "3", "4"))) must throwA[ParseException].like {
        case e => e.getMessage must contain("invalid drilldown query List(List(1, 2), 3, 4)")
      }
    }

    "return error on List[List[NotAString]]" in {
      IndexService.getDrilldown(drilldown(List(List(1, 2)))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(1, 2) contains non-string element 1")
      }
    }

    "return error on List[List[MixOfStringsAndNoneStrings]]" in {
      IndexService.getDrilldown(drilldown(List(List("1", 2, "3")))) must throwA[ParseException].like {
        case e => e.getMessage must contain("List(1, 2, 3) contains non-string element 2")
      }
    }

  }

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
      val facets = new SortedSetDocValuesFacetFields(FacetIndexingParams.DEFAULT)
      val doc1 = new Document()
      doc1.add(new StringField("_id", "foo", Field.Store.YES))
      doc1.add(new StringField("ffield", "f1", Field.Store.YES))
      facets.addFields(doc1, List(new CategoryPath("ffield", "f1")).asJava)

      val doc2 = new Document()
      doc2.add(new StringField("_id", "foo2", Field.Store.YES))
      doc2.add(new StringField("ffield", "f1", Field.Store.YES))
      facets.addFields(doc2, List(new CategoryPath("ffield", "f1")).asJava)

      val doc3 = new Document()
      doc3.add(new StringField("_id", "foo3", Field.Store.YES))
      doc3.add(new StringField("ffield", "f3", Field.Store.YES))
      facets.addFields(doc3, List(new CategoryPath("ffield", "f3")).asJava)

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

  private def indexNotClosedAfterTimeout(node: Node, service: Pid) = {
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

  private def indexClosedAfterTimeOut(node: Node, service: Pid) = {
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
                                                              service: Pid) = {
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

  def options(): AnalyzerOptions = {
    AnalyzerOptions.fromAnalyzerName("standard")
  }

  override def after {
    if (service != null) {
      node.send(service, 'delete)
    }
    super.after
  }

}

trait index_service_perfield extends index_service {

  override def options(): AnalyzerOptions = {
    AnalyzerOptions.fromMap(Map("name" -> "perfield", "default" -> "english"))
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
