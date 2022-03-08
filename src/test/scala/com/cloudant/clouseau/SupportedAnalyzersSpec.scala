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
      createAnalyzer("foo") must beNone
    }
    "List of non-tuples yields no analyzer" in {
      createAnalyzer(List("foo")) must beNone
    }
    "keyword" in {
      createAnalyzer("keyword") must haveClass[Some[KeywordAnalyzer]]
    }
    "simple" in {
      createAnalyzer("simple") must haveClass[Some[SimpleAnalyzer]]
    }
    "whitespace" in {
      createAnalyzer("whitespace") must haveClass[Some[WhitespaceAnalyzer]]
    }
    "simple_asciifolding" in {
      createAnalyzer("simple_asciifolding") must haveClass[Some[Analyzer]]
    }
    "email" in {
      createAnalyzer("email") must haveClass[Some[UAX29URLEmailAnalyzer]]
    }
    "perfield" in {
      // basic
      createAnalyzer("perfield") must haveClass[Some[PerFieldAnalyzer]]

      // override default
      createAnalyzer(Map("name" -> "perfield", "default" -> "english")).toString must
        contain("default=org.apache.lucene.analysis.en.EnglishAnalyzer")

      // override field
      createAnalyzer(Map("name" -> "perfield", "fields" -> List("foo" -> "english"))).toString must
        contain("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")

      // unrecognized per-field throws an IllegalArgumentException("no_such_analyzer")
      createAnalyzer(Map("name" -> "perfield", "default" -> "english", "fields" -> List("foo" -> "foo"))) must
        throwAn[IllegalArgumentException]
    }
    "arabic" in {
      createAnalyzer("arabic") must haveClass[Some[ArabicAnalyzer]]
    }
    "bulgarian" in {
      createAnalyzer("bulgarian") must haveClass[Some[BulgarianAnalyzer]]
    }
    "brazilian" in {
      createAnalyzer("brazilian") must haveClass[Some[BrazilianAnalyzer]]
    }
    "catalan" in {
      createAnalyzer("catalan") must haveClass[Some[CatalanAnalyzer]]
    }
    "cjk" in {
      createAnalyzer("cjk") must haveClass[Some[CJKAnalyzer]]
    }
    "chinese" in {
      createAnalyzer("chinese") must haveClass[Some[SmartChineseAnalyzer]]
    }
    "czech" in {
      createAnalyzer("czech") must haveClass[Some[CzechAnalyzer]]
    }
    "danish" in {
      createAnalyzer("danish") must haveClass[Some[DanishAnalyzer]]
    }
    "german" in {
      createAnalyzer("german") must haveClass[Some[GermanAnalyzer]]
    }
    "greek" in {
      createAnalyzer("greek") must haveClass[Some[GreekAnalyzer]]
    }
    "english" in {
      createAnalyzer("english") must haveClass[Some[EnglishAnalyzer]]
    }
    "spanish" in {
      createAnalyzer("spanish") must haveClass[Some[SpanishAnalyzer]]
    }
    "basque" in {
      createAnalyzer("basque") must haveClass[Some[BasqueAnalyzer]]
    }
    "persian" in {
      createAnalyzer("persian") must haveClass[Some[PersianAnalyzer]]
    }
    "finnish" in {
      createAnalyzer("finnish") must haveClass[Some[FinnishAnalyzer]]
    }
    "french" in {
      createAnalyzer("french") must haveClass[Some[FrenchAnalyzer]]
    }
    "irish" in {
      createAnalyzer("irish") must haveClass[Some[IrishAnalyzer]]
    }
    "galician" in {
      createAnalyzer("galician") must haveClass[Some[GalicianAnalyzer]]
    }
    "hindi" in {
      createAnalyzer("hindi") must haveClass[Some[HindiAnalyzer]]
    }
    "hungarian" in {
      createAnalyzer("hungarian") must haveClass[Some[HungarianAnalyzer]]
    }
    "armenian" in {
      createAnalyzer("armenian") must haveClass[Some[ArmenianAnalyzer]]
    }
    "indonesian" in {
      createAnalyzer("indonesian") must haveClass[Some[IndonesianAnalyzer]]
    }
    "italian" in {
      createAnalyzer("italian") must haveClass[Some[ItalianAnalyzer]]
    }
    "japanese" in {
      createAnalyzer("japanese") must haveClass[Some[JapaneseAnalyzer]]
    }
    "latvian" in {
      createAnalyzer("latvian") must haveClass[Some[LatvianAnalyzer]]
    }
    "dutch" in {
      createAnalyzer("dutch") must haveClass[Some[DutchAnalyzer]]
    }
    "norwegian" in {
      createAnalyzer("norwegian") must haveClass[Some[NorwegianAnalyzer]]
    }
    "polish" in {
      createAnalyzer("polish") must haveClass[Some[PolishAnalyzer]]
    }
    "portuguese" in {
      createAnalyzer("portuguese") must haveClass[Some[PortugueseAnalyzer]]
    }
    "romanian" in {
      createAnalyzer("romanian") must haveClass[Some[RomanianAnalyzer]]
    }
    "russian" in {
      createAnalyzer("russian") must haveClass[Some[RussianAnalyzer]]
    }
    "classic" in {
      createAnalyzer("classic") must haveClass[Some[ClassicAnalyzer]]
    }
    "standard" in {
      createAnalyzer("standard") must haveClass[Some[StandardAnalyzer]]
    }
    "swedish" in {
      createAnalyzer("swedish") must haveClass[Some[SwedishAnalyzer]]
    }
    "thai" in {
      createAnalyzer("thai") must haveClass[Some[ThaiAnalyzer]]
    }
    "turkish" in {
      createAnalyzer("turkish") must haveClass[Some[TurkishAnalyzer]]
    }

  }
}
