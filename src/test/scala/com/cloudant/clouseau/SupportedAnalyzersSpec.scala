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
import org.specs2.mutable.SpecificationWithJUnit

class SupportedAnalyzersSpec extends SpecificationWithJUnit {
  "SupportedAnalyzers" should {

    "ignore unsupported analyzers" in {
      val options = AnalyzerOptions.from("foo")
      options must beSome
      createAnalyzer(options.get) must beNone
    }
    "List of non-tuples yields no analyzer" in {
      val options = AnalyzerOptions.from(List("foo"))
      options must beSome
      createAnalyzer(options.get) must beNone
    }
    "keyword" in {
      val options = AnalyzerOptions.from("keyword")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[KeywordAnalyzer]]
    }
    "simple" in {
      val options = AnalyzerOptions.from("simple")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[SimpleAnalyzer]]
    }
    "whitespace" in {
      val options = AnalyzerOptions.from("whitespace")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[WhitespaceAnalyzer]]
    }
    "simple_asciifolding" in {
      val options = AnalyzerOptions.from("simple_asciifolding")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[Analyzer]]
    }
    "email" in {
      val options = AnalyzerOptions.from("email")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[UAX29URLEmailAnalyzer]]
    }
    "perfield" in {
      // basic
      val basicOptions = AnalyzerOptions.from("perfield")
      basicOptions must beSome
      createAnalyzer(basicOptions.get) must haveClass[Some[PerFieldAnalyzer]]

      // override default
      val overrideDefaultOptions = AnalyzerOptions.from(Map("name" -> "perfield", "default" -> "english"))
      overrideDefaultOptions must beSome
      createAnalyzer(overrideDefaultOptions.get).toString must
        contain("default=org.apache.lucene.analysis.en.EnglishAnalyzer")

      // override field
      val overrideFieldOptions = AnalyzerOptions.from(Map("name" -> "perfield", "fields" -> List("foo" -> "english")))
      overrideFieldOptions must beSome
      createAnalyzer(overrideFieldOptions.get).toString must
        contain("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")

      // unrecognized per-field becomes default
      val unrecognizedOptions = AnalyzerOptions.from(Map("name" -> "perfield", "default" -> "english", "fields" -> List("foo" -> "foo")))
      unrecognizedOptions must beSome
      createAnalyzer(unrecognizedOptions.get).toString must
        contain("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")
    }
    "arabic" in {
      val options = AnalyzerOptions.from("arabic")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[ArabicAnalyzer]]
    }
    "bulgarian" in {
      val options = AnalyzerOptions.from("bulgarian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[BulgarianAnalyzer]]
    }
    "brazilian" in {
      val options = AnalyzerOptions.from("brazilian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[BrazilianAnalyzer]]
    }
    "catalan" in {
      val options = AnalyzerOptions.from("catalan")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[CatalanAnalyzer]]
    }
    "cjk" in {
      val options = AnalyzerOptions.from("cjk")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[CJKAnalyzer]]
    }
    "chinese" in {
      val options = AnalyzerOptions.from("chinese")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[SmartChineseAnalyzer]]
    }
    "czech" in {
      val options = AnalyzerOptions.from("czech")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[CzechAnalyzer]]
    }
    "danish" in {
      val options = AnalyzerOptions.from("danish")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[DanishAnalyzer]]
    }
    "german" in {
      val options = AnalyzerOptions.from("german")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[GermanAnalyzer]]
    }
    "greek" in {
      val options = AnalyzerOptions.from("greek")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[GreekAnalyzer]]
    }
    "english" in {
      val options = AnalyzerOptions.from("english")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[EnglishAnalyzer]]
    }
    "spanish" in {
      val options = AnalyzerOptions.from("spanish")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[SpanishAnalyzer]]
    }
    "basque" in {
      val options = AnalyzerOptions.from("basque")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[BasqueAnalyzer]]
    }
    "persian" in {
      val options = AnalyzerOptions.from("persian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[PersianAnalyzer]]
    }
    "finnish" in {
      val options = AnalyzerOptions.from("finnish")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[FinnishAnalyzer]]
    }
    "french" in {
      val options = AnalyzerOptions.from("french")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[FrenchAnalyzer]]
    }
    "irish" in {
      val options = AnalyzerOptions.from("irish")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[IrishAnalyzer]]
    }
    "galician" in {
      val options = AnalyzerOptions.from("galician")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[GalicianAnalyzer]]
    }
    "hindi" in {
      val options = AnalyzerOptions.from("hindi")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[HindiAnalyzer]]
    }
    "hungarian" in {
      val options = AnalyzerOptions.from("hungarian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[HungarianAnalyzer]]
    }
    "armenian" in {
      val options = AnalyzerOptions.from("armenian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[ArmenianAnalyzer]]
    }
    "indonesian" in {
      val options = AnalyzerOptions.from("indonesian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[IndonesianAnalyzer]]
    }
    "italian" in {
      val options = AnalyzerOptions.from("italian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[ItalianAnalyzer]]
    }
    "japanese" in {
      val options = AnalyzerOptions.from("japanese")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[JapaneseAnalyzer]]
    }
    "latvian" in {
      val options = AnalyzerOptions.from("latvian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[LatvianAnalyzer]]
    }
    "dutch" in {
      val options = AnalyzerOptions.from("dutch")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[DutchAnalyzer]]
    }
    "norwegian" in {
      val options = AnalyzerOptions.from("norwegian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[NorwegianAnalyzer]]
    }
    "polish" in {
      val options = AnalyzerOptions.from("polish")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[PolishAnalyzer]]
    }
    "portuguese" in {
      val options = AnalyzerOptions.from("portuguese")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[PortugueseAnalyzer]]
    }
    "romanian" in {
      val options = AnalyzerOptions.from("romanian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[RomanianAnalyzer]]
    }
    "russian" in {
      val options = AnalyzerOptions.from("russian")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[RussianAnalyzer]]
    }
    "classic" in {
      val options = AnalyzerOptions.from("classic")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[ClassicAnalyzer]]
    }
    "standard" in {
      val options = AnalyzerOptions.from("standard")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[StandardAnalyzer]]
    }
    "swedish" in {
      val options = AnalyzerOptions.from("swedish")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[SwedishAnalyzer]]
    }
    "thai" in {
      val options = AnalyzerOptions.from("thai")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[ThaiAnalyzer]]
    }
    "turkish" in {
      val options = AnalyzerOptions.from("turkish")
      options must beSome
      createAnalyzer(options.get) must haveClass[Some[TurkishAnalyzer]]
    }
  }
}
