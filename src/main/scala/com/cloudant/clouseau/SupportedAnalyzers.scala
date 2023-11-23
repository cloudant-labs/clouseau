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

import java.io.Reader
import java.util.{ Set => JSet }
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.Tokenizer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents
import org.apache.lucene.analysis.util.CharArraySet
import scala.collection.JavaConversions._

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

// Extras
import org.apache.lucene.analysis.ja.JapaneseTokenizer
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.core.LowerCaseFilter
import org.apache.lucene.analysis.core.LetterTokenizer

object SupportedAnalyzers {
  val logger = LoggerFactory.getLogger("clouseau.analyzers")

  def createAnalyzerFromString(name: String): Option[Analyzer] = {
    createAnalyzer(Map("name" -> name))
  }

  def createAnalyzer(options: Map[String, Any]): Option[Analyzer] = {
    createAnalyzerInt(options) match {
      case Some(perfield: PerFieldAnalyzer) =>
        Some(perfield)
      case Some(analyzer: Analyzer) =>
        Some(new PerFieldAnalyzer(analyzer,
          Map("_id" -> new KeywordAnalyzer(),
            "_partition" -> new KeywordAnalyzer())))
      case None =>
        None
    }
  }

  def createAnalyzerFromStringInt(name: String): Option[Analyzer] = {
    createAnalyzerInt(Map("name" -> name))
  }

  def createAnalyzerInt(options: Map[String, Any]): Option[Analyzer] = options.get("name").map(_.asInstanceOf[String]) match {
    case Some(name: String) =>
      createAnalyzerInt(name, options)
    case None =>
      None
  }

  def createAnalyzerInt(name: String, options: Map[String, Any]): Option[Analyzer] = name match {
    case "keyword" =>
      Some(new KeywordAnalyzer())
    case "simple" =>
      Some(new SimpleAnalyzer(IndexService.version))
    case "whitespace" =>
      Some(new WhitespaceAnalyzer(IndexService.version))
    case "simple_asciifolding" =>
      Some(new Analyzer() {
        def createComponents(fieldName: String, reader: Reader): TokenStreamComponents = {
          val tokenizer: Tokenizer = new LetterTokenizer(IndexService.version, reader);
          new TokenStreamComponents(tokenizer, new ASCIIFoldingFilter(new LowerCaseFilter(IndexService.version, tokenizer)))
        }
      })
    case "arabic" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ArabicAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new ArabicAnalyzer(IndexService.version))
      }
    case "bulgarian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new BulgarianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new BulgarianAnalyzer(IndexService.version))
      }
    case "brazilian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new BrazilianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new BrazilianAnalyzer(IndexService.version))
      }
    case "catalan" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new CatalanAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new CatalanAnalyzer(IndexService.version))
      }
    case "cjk" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new CJKAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new CJKAnalyzer(IndexService.version))
      }
    case "chinese" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new SmartChineseAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new SmartChineseAnalyzer(IndexService.version))
      }
    case "czech" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new CzechAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new CzechAnalyzer(IndexService.version))
      }
    case "danish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new DanishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new DanishAnalyzer(IndexService.version))
      }
    case "german" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new GermanAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new GermanAnalyzer(IndexService.version))
      }
    case "greek" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new GreekAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new GreekAnalyzer(IndexService.version))
      }
    case "english" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new EnglishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new EnglishAnalyzer(IndexService.version))
      }
    case "spanish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new SpanishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new SpanishAnalyzer(IndexService.version))
      }
    case "basque" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new BasqueAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new BasqueAnalyzer(IndexService.version))
      }
    case "persian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new PersianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new PersianAnalyzer(IndexService.version))
      }
    case "finnish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new FinnishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new FinnishAnalyzer(IndexService.version))
      }
    case "french" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new FrenchAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new FrenchAnalyzer(IndexService.version))
      }
    case "irish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new IrishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new IrishAnalyzer(IndexService.version))
      }
    case "galician" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new GalicianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new GalicianAnalyzer(IndexService.version))
      }
    case "hindi" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new HindiAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new HindiAnalyzer(IndexService.version))
      }
    case "hungarian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new HungarianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new HungarianAnalyzer(IndexService.version))
      }
    case "armenian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ArmenianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new ArmenianAnalyzer(IndexService.version))
      }
    case "indonesian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new IndonesianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new IndonesianAnalyzer(IndexService.version))
      }
    case "italian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ItalianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new ItalianAnalyzer(IndexService.version))
      }
    case "japanese" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new JapaneseAnalyzer(IndexService.version, null, JapaneseTokenizer.DEFAULT_MODE, stopwords, JapaneseAnalyzer.getDefaultStopTags))
        case None =>
          Some(new JapaneseAnalyzer(IndexService.version))
      }
    case "latvian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new LatvianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new LatvianAnalyzer(IndexService.version))
      }
    case "dutch" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new DutchAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new DutchAnalyzer(IndexService.version))
      }
    case "norwegian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new NorwegianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new NorwegianAnalyzer(IndexService.version))
      }
    case "polish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new PolishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new PolishAnalyzer(IndexService.version))
      }
    case "portuguese" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new PortugueseAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new PortugueseAnalyzer(IndexService.version))
      }
    case "romanian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new RomanianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new RomanianAnalyzer(IndexService.version))
      }
    case "russian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new RussianAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new RussianAnalyzer(IndexService.version))
      }
    case "classic" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ClassicAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new ClassicAnalyzer(IndexService.version))
      }
    case "standard" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new StandardAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new StandardAnalyzer(IndexService.version))
      }
    case "email" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new UAX29URLEmailAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new UAX29URLEmailAnalyzer(IndexService.version))
      }
    case "perfield" =>
      val fallbackAnalyzer = new StandardAnalyzer(IndexService.version)
      // The "default" field is always a string
      val defaultAnalyzer: Analyzer = options.get("default").map(_.asInstanceOf[String]) match {
        case Some(defaultOptions: String) =>
          createAnalyzerFromStringInt(defaultOptions) match {
            case Some(defaultAnalyzer1) =>
              defaultAnalyzer1
            case None =>
              fallbackAnalyzer
          }
        case None =>
          fallbackAnalyzer
      }
      // The Per-field analyzers are always strings
      var fieldMap: Map[String, Analyzer] = options.get("fields") match {
        case Some(fields: List[_]) =>
          // ignore all fields of non string type
          fields.collect {
            case (field: String, analyzerName: String) =>
              createAnalyzerFromStringInt(analyzerName) match {
                case Some(fieldAnalyzer) =>
                  (field, fieldAnalyzer)
                case None =>
                  (field, defaultAnalyzer)
              }
          }.toMap
        case _ =>
          Map.empty
      }
      fieldMap += ("_id" -> new KeywordAnalyzer())
      fieldMap += ("_partition" -> new KeywordAnalyzer())
      Some(new PerFieldAnalyzer(defaultAnalyzer, fieldMap))
    case "swedish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new SwedishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new SwedishAnalyzer(IndexService.version))
      }
    case "thai" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ThaiAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new ThaiAnalyzer(IndexService.version))
      }
    case "turkish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new TurkishAnalyzer(IndexService.version, stopwords))
        case None =>
          Some(new TurkishAnalyzer(IndexService.version))
      }
    case _ =>
      None
  }

  implicit def listToJavaSet(list: List[String]): JSet[String] = {
    Set() ++ list
  }

  implicit def listToCharArraySet(list: List[String]): CharArraySet = {
    CharArraySet.unmodifiableSet(CharArraySet.copy(IndexService.version, Set() ++ list))
  }

  def getStopWords(options: Map[String, Any]): Option[CharArraySet] =
    options.get("stopwords").collect { case list: List[_] => list.collect { case word: String => word } }
}
