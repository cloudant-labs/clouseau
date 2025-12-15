/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.SupportedAnalyzersSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.core.KeywordAnalyzer
import org.apache.lucene.analysis.core.SimpleAnalyzer
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.analysis.ar.ArabicAnalyzer
import org.apache.lucene.analysis.bg.BulgarianAnalyzer
import org.apache.lucene.analysis.br.BrazilianAnalyzer
import org.apache.lucene.analysis.ca.CatalanAnalyzer
import org.apache.lucene.analysis.cjk.CJKAnalyzer
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer
import org.apache.lucene.analysis.cz.CzechAnalyzer
import org.apache.lucene.analysis.da.DanishAnalyzer
import org.apache.lucene.analysis.de.GermanAnalyzer
import org.apache.lucene.analysis.el.GreekAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.es.SpanishAnalyzer
import org.apache.lucene.analysis.eu.BasqueAnalyzer
import org.apache.lucene.analysis.fa.PersianAnalyzer
import org.apache.lucene.analysis.fi.FinnishAnalyzer
import org.apache.lucene.analysis.fr.FrenchAnalyzer
import org.apache.lucene.analysis.ga.IrishAnalyzer
import org.apache.lucene.analysis.gl.GalicianAnalyzer
import org.apache.lucene.analysis.hi.HindiAnalyzer
import org.apache.lucene.analysis.hu.HungarianAnalyzer
import org.apache.lucene.analysis.hy.ArmenianAnalyzer
import org.apache.lucene.analysis.id.IndonesianAnalyzer
import org.apache.lucene.analysis.it.ItalianAnalyzer
import org.apache.lucene.analysis.ja.JapaneseAnalyzer
import org.apache.lucene.analysis.lv.LatvianAnalyzer
import org.apache.lucene.analysis.nl.DutchAnalyzer
import org.apache.lucene.analysis.no.NorwegianAnalyzer
import org.apache.lucene.analysis.pl.PolishAnalyzer
import org.apache.lucene.analysis.pt.PortugueseAnalyzer
import org.apache.lucene.analysis.ro.RomanianAnalyzer
import org.apache.lucene.analysis.ru.RussianAnalyzer
import org.apache.lucene.analysis.standard.ClassicAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.standard.UAX29URLEmailAnalyzer
import org.apache.lucene.analysis.sv.SwedishAnalyzer
import org.apache.lucene.analysis.th.ThaiAnalyzer
import org.apache.lucene.analysis.tr.TurkishAnalyzer

import SupportedAnalyzers._

import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class SupportedAnalyzersSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes

  val supportedAnalyzersSuite: Spec[Any, Throwable] = {
    suite("SupportedAnalyzers")(
      test("ignore unsupported analyzers") {
        val options = AnalyzerOptions.from("foo")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isNone)
      },
      test("List of non-tuples yields no analyzer") {
        val options = AnalyzerOptions.from(List("foo"))
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isNone)
      },
      test("keyword") {
        val options = AnalyzerOptions.from("keyword")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[KeywordAnalyzer]](anything))
      },
      test("simple") {
        val options = AnalyzerOptions.from("simple")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[SimpleAnalyzer]](anything))
      },
      test("whitespace") {
        val options = AnalyzerOptions.from("whitespace")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[WhitespaceAnalyzer]](anything))
      },
      test("simple_asciifolding") {
        val options = AnalyzerOptions.from("simple_asciifolding")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[Analyzer]](anything))
      },
      test("email") {
        val options = AnalyzerOptions.from("email")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[UAX29URLEmailAnalyzer]](anything))
      },
      test("perfield, basic") {
        val options = AnalyzerOptions.from("perfield")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[PerFieldAnalyzer]](anything))
      },
      test("perfield, override default") {
        val options = AnalyzerOptions.from(Map("name" -> "perfield", "default" -> "english"))
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get).toString)(
          containsString("default=org.apache.lucene.analysis.en.EnglishAnalyzer")
        )
      },
      test("perfield, override field") {
        val options = AnalyzerOptions.from(Map("name" -> "perfield", "fields" -> List("foo" -> "english")))
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get).toString)(
          containsString("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")
        )
      },
      test("perfield, unrecognized per-field becomes default") {
        val options = {
          AnalyzerOptions.from(Map("name" -> "perfield", "default" -> "english", "fields" -> List("foo" -> "foo")))
        }
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get).toString)(
          containsString("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")
        )
      },
      test("perfield, field in object form") {
        val options = {
          AnalyzerOptions.from(Map("name" -> "perfield", "default" -> "standard",
            "fields" -> List("foo" -> List(("name", "english")))))
        }
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get).toString)(
          containsString("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")
        )
      },
      test("perfield, default in object form") {
        val options = {
          AnalyzerOptions.from(Map("name" -> "perfield", "default" -> List(("name", "english"))))
        }
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get).toString)(
          containsString("default=org.apache.lucene.analysis.en.EnglishAnalyzer")
        )
      },
      test("arabic") {
        val options = AnalyzerOptions.from("arabic")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[ArabicAnalyzer]](anything))
      },
      test("bulgarian") {
        val options = AnalyzerOptions.from("bulgarian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[BulgarianAnalyzer]](anything))
      },
      test("brazilian") {
        val options = AnalyzerOptions.from("brazilian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[BrazilianAnalyzer]](anything))
      },
      test("catalan") {
        val options = AnalyzerOptions.from("catalan")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[CatalanAnalyzer]](anything))
      },
      test("cjk") {
        val options = AnalyzerOptions.from("cjk")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[CJKAnalyzer]](anything))
      },
      test("chinese") {
        val options = AnalyzerOptions.from("chinese")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[SmartChineseAnalyzer]](anything))
      },
      test("czech") {
        val options = AnalyzerOptions.from("czech")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[CzechAnalyzer]](anything))
      },
      test("danish") {
        val options = AnalyzerOptions.from("danish")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[DanishAnalyzer]](anything))
      },
      test("german") {
        val options = AnalyzerOptions.from("german")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[GermanAnalyzer]](anything))
      },
      test("greek") {
        val options = AnalyzerOptions.from("greek")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[GreekAnalyzer]](anything))
      },
      test("english") {
        val options = AnalyzerOptions.from("english")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[EnglishAnalyzer]](anything))
      },
      test("spanish") {
        val options = AnalyzerOptions.from("spanish")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[SpanishAnalyzer]](anything))
      },
      test("basque") {
        val options = AnalyzerOptions.from("basque")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[BasqueAnalyzer]](anything))
      },
      test("persian") {
        val options = AnalyzerOptions.from("persian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[PersianAnalyzer]](anything))
      },
      test("finnish") {
        val options = AnalyzerOptions.from("finnish")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[FinnishAnalyzer]](anything))
      },
      test("french") {
        val options = AnalyzerOptions.from("french")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[FrenchAnalyzer]](anything))
      },
      test("irish") {
        val options = AnalyzerOptions.from("irish")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[IrishAnalyzer]](anything))
      },
      test("galician") {
        val options = AnalyzerOptions.from("galician")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[GalicianAnalyzer]](anything))
      },
      test("hindi") {
        val options = AnalyzerOptions.from("hindi")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[HindiAnalyzer]](anything))
      },
      test("hungarian") {
        val options = AnalyzerOptions.from("hungarian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[HungarianAnalyzer]](anything))
      },
      test("armenian") {
        val options = AnalyzerOptions.from("armenian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[ArmenianAnalyzer]](anything))
      },
      test("indonesian") {
        val options = AnalyzerOptions.from("indonesian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[IndonesianAnalyzer]](anything))
      },
      test("italian") {
        val options = AnalyzerOptions.from("italian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[ItalianAnalyzer]](anything))
      },
      test("japanese") {
        val options = AnalyzerOptions.from("japanese")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[JapaneseAnalyzer]](anything))
      },
      test("latvian") {
        val options = AnalyzerOptions.from("latvian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[LatvianAnalyzer]](anything))
      },
      test("dutch") {
        val options = AnalyzerOptions.from("dutch")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[DutchAnalyzer]](anything))
      },
      test("norwegian") {
        val options = AnalyzerOptions.from("norwegian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[NorwegianAnalyzer]](anything))
      },
      test("polish") {
        val options = AnalyzerOptions.from("polish")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[PolishAnalyzer]](anything))
      },
      test("portuguese") {
        val options = AnalyzerOptions.from("portuguese")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[PortugueseAnalyzer]](anything))
      },
      test("romanian") {
        val options = AnalyzerOptions.from("romanian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[RomanianAnalyzer]](anything))
      },
      test("russian") {
        val options = AnalyzerOptions.from("russian")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[RussianAnalyzer]](anything))
      },
      test("classic") {
        val options = AnalyzerOptions.from("classic")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[ClassicAnalyzer]](anything))
      },
      test("standard") {
        val options = AnalyzerOptions.from("standard")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[StandardAnalyzer]](anything))
      },
      test("swedish") {
        val options = AnalyzerOptions.from("swedish")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[SwedishAnalyzer]](anything))
      },
      test("thai") {
        val options = AnalyzerOptions.from("thai")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[ThaiAnalyzer]](anything))
      },
      test("turkish") {
        val options = AnalyzerOptions.from("turkish")
        assert(options)(isSome) &&
        assert(createAnalyzer(options.get))(isSubtype[Some[TurkishAnalyzer]](anything))
      }
    )
  }

  def spec: Spec[Any, Throwable] = {
    suite("SupportedAnalyzersSpec")(
      supportedAnalyzersSuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.SupportedAnalyzersSpecMain
 * ```
 */
object SupportedAnalyzersSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("SupportedAnalyzersSpec", new SupportedAnalyzersSpec().spec)
  }
}
