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
      createAnalyzerFromString("foo") must beNone
    }
    "keyword" in {
      createAnalyzerFromString("keyword") must haveClass[Some[KeywordAnalyzer]]
    }
    "simple" in {
      createAnalyzerFromString("simple") must haveClass[Some[SimpleAnalyzer]]
    }
    "whitespace" in {
      createAnalyzerFromString("whitespace") must haveClass[Some[WhitespaceAnalyzer]]
    }
    "simple_asciifolding" in {
      createAnalyzerFromString("simple_asciifolding") must haveClass[Some[Analyzer]]
    }
    "email" in {
      createAnalyzerFromString("email") must haveClass[Some[UAX29URLEmailAnalyzer]]
    }
    "perfield" in {
      // basic
      createAnalyzerFromString("perfield") must haveClass[Some[PerFieldAnalyzer]]

      // override default
      createAnalyzer(Map("name" -> "perfield", "default" -> "english")).toString must
        contain("default=org.apache.lucene.analysis.en.EnglishAnalyzer")

      // override field
      createAnalyzer(Map("name" -> "perfield", "fields" -> List("foo" -> "english"))).toString must
        contain("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")

      // unrecognized per-field becomes default
      createAnalyzer(Map("name" -> "perfield", "default" -> "english", "fields" -> List("foo" -> "foo"))).toString must
        contain("foo -> org.apache.lucene.analysis.en.EnglishAnalyzer")
    }
    "arabic" in {
      createAnalyzerFromString("arabic") must haveClass[Some[ArabicAnalyzer]]
    }
    "bulgarian" in {
      createAnalyzerFromString("bulgarian") must haveClass[Some[BulgarianAnalyzer]]
    }
    "brazilian" in {
      createAnalyzerFromString("brazilian") must haveClass[Some[BrazilianAnalyzer]]
    }
    "catalan" in {
      createAnalyzerFromString("catalan") must haveClass[Some[CatalanAnalyzer]]
    }
    "cjk" in {
      createAnalyzerFromString("cjk") must haveClass[Some[CJKAnalyzer]]
    }
    "chinese" in {
      createAnalyzerFromString("chinese") must haveClass[Some[SmartChineseAnalyzer]]
    }
    "czech" in {
      createAnalyzerFromString("czech") must haveClass[Some[CzechAnalyzer]]
    }
    "danish" in {
      createAnalyzerFromString("danish") must haveClass[Some[DanishAnalyzer]]
    }
    "german" in {
      createAnalyzerFromString("german") must haveClass[Some[GermanAnalyzer]]
    }
    "greek" in {
      createAnalyzerFromString("greek") must haveClass[Some[GreekAnalyzer]]
    }
    "english" in {
      createAnalyzerFromString("english") must haveClass[Some[EnglishAnalyzer]]
    }
    "spanish" in {
      createAnalyzerFromString("spanish") must haveClass[Some[SpanishAnalyzer]]
    }
    "basque" in {
      createAnalyzerFromString("basque") must haveClass[Some[BasqueAnalyzer]]
    }
    "persian" in {
      createAnalyzerFromString("persian") must haveClass[Some[PersianAnalyzer]]
    }
    "finnish" in {
      createAnalyzerFromString("finnish") must haveClass[Some[FinnishAnalyzer]]
    }
    "french" in {
      createAnalyzerFromString("french") must haveClass[Some[FrenchAnalyzer]]
    }
    "irish" in {
      createAnalyzerFromString("irish") must haveClass[Some[IrishAnalyzer]]
    }
    "galician" in {
      createAnalyzerFromString("galician") must haveClass[Some[GalicianAnalyzer]]
    }
    "hindi" in {
      createAnalyzerFromString("hindi") must haveClass[Some[HindiAnalyzer]]
    }
    "hungarian" in {
      createAnalyzerFromString("hungarian") must haveClass[Some[HungarianAnalyzer]]
    }
    "armenian" in {
      createAnalyzerFromString("armenian") must haveClass[Some[ArmenianAnalyzer]]
    }
    "indonesian" in {
      createAnalyzerFromString("indonesian") must haveClass[Some[IndonesianAnalyzer]]
    }
    "italian" in {
      createAnalyzerFromString("italian") must haveClass[Some[ItalianAnalyzer]]
    }
    "japanese" in {
      createAnalyzerFromString("japanese") must haveClass[Some[JapaneseAnalyzer]]
    }
    "latvian" in {
      createAnalyzerFromString("latvian") must haveClass[Some[LatvianAnalyzer]]
    }
    "dutch" in {
      createAnalyzerFromString("dutch") must haveClass[Some[DutchAnalyzer]]
    }
    "norwegian" in {
      createAnalyzerFromString("norwegian") must haveClass[Some[NorwegianAnalyzer]]
    }
    "polish" in {
      createAnalyzerFromString("polish") must haveClass[Some[PolishAnalyzer]]
    }
    "portuguese" in {
      createAnalyzerFromString("portuguese") must haveClass[Some[PortugueseAnalyzer]]
    }
    "romanian" in {
      createAnalyzerFromString("romanian") must haveClass[Some[RomanianAnalyzer]]
    }
    "russian" in {
      createAnalyzerFromString("russian") must haveClass[Some[RussianAnalyzer]]
    }
    "classic" in {
      createAnalyzerFromString("classic") must haveClass[Some[ClassicAnalyzer]]
    }
    "standard" in {
      createAnalyzerFromString("standard") must haveClass[Some[StandardAnalyzer]]
    }
    "swedish" in {
      createAnalyzerFromString("swedish") must haveClass[Some[SwedishAnalyzer]]
    }
    "thai" in {
      createAnalyzerFromString("thai") must haveClass[Some[ThaiAnalyzer]]
    }
    "turkish" in {
      createAnalyzerFromString("turkish") must haveClass[Some[TurkishAnalyzer]]
    }

  }
}
