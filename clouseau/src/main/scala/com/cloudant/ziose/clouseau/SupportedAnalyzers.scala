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

package com.cloudant.ziose.clouseau

import java.util.{ Set => JSet }
//import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.Tokenizer
//import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents
import org.apache.lucene.analysis.CharArraySet
import scala.collection.JavaConverters._

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
import org.apache.lucene.analysis.classic.ClassicAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.email.UAX29URLEmailAnalyzer
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

  def createAnalyzer(options: AnalyzerOptions): Option[Analyzer] = {
    createAnalyzerInt(options.toMap) match {
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

  def createAnalyzerInt(options: Map[String, Any]): Option[Analyzer] =
    options.get("name").map(_.asInstanceOf[String]) match {
      case Some(name: String) =>
        createAnalyzerInt(name, options)
      case None =>
        None
    }

  def createAnalyzerInt(name: String, options: Map[String, Any]): Option[Analyzer] = name match {
    case "keyword" =>
      Some(new KeywordAnalyzer())
    case "simple" =>
      Some(new SimpleAnalyzer())
    case "whitespace" =>
      Some(new WhitespaceAnalyzer())
    case "simple_asciifolding" =>
      Some(new Analyzer() {
        def createComponents(fieldName: String): TokenStreamComponents = {
          val tokenizer: Tokenizer = new LetterTokenizer()
          new TokenStreamComponents(tokenizer, new ASCIIFoldingFilter(new LowerCaseFilter(tokenizer)))
        }
      })
    case "arabic" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ArabicAnalyzer(stopwords))
        case None =>
          Some(new ArabicAnalyzer())
      }
    case "bulgarian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new BulgarianAnalyzer(stopwords))
        case None =>
          Some(new BulgarianAnalyzer())
      }
    case "brazilian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new BrazilianAnalyzer(stopwords))
        case None =>
          Some(new BrazilianAnalyzer())
      }
    case "catalan" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new CatalanAnalyzer(stopwords))
        case None =>
          Some(new CatalanAnalyzer())
      }
    case "cjk" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new CJKAnalyzer(stopwords))
        case None =>
          Some(new CJKAnalyzer())
      }
    case "chinese" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new SmartChineseAnalyzer(stopwords))
        case None =>
          Some(new SmartChineseAnalyzer())
      }
    case "czech" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new CzechAnalyzer(stopwords))
        case None =>
          Some(new CzechAnalyzer())
      }
    case "danish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new DanishAnalyzer(stopwords))
        case None =>
          Some(new DanishAnalyzer())
      }
    case "german" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new GermanAnalyzer(stopwords))
        case None =>
          Some(new GermanAnalyzer())
      }
    case "greek" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new GreekAnalyzer(stopwords))
        case None =>
          Some(new GreekAnalyzer())
      }
    case "english" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new EnglishAnalyzer(stopwords))
        case None =>
          Some(new EnglishAnalyzer())
      }
    case "spanish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new SpanishAnalyzer(stopwords))
        case None =>
          Some(new SpanishAnalyzer())
      }
    case "basque" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new BasqueAnalyzer(stopwords))
        case None =>
          Some(new BasqueAnalyzer())
      }
    case "persian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new PersianAnalyzer(stopwords))
        case None =>
          Some(new PersianAnalyzer())
      }
    case "finnish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new FinnishAnalyzer(stopwords))
        case None =>
          Some(new FinnishAnalyzer())
      }
    case "french" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new FrenchAnalyzer(stopwords))
        case None =>
          Some(new FrenchAnalyzer())
      }
    case "irish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new IrishAnalyzer(stopwords))
        case None =>
          Some(new IrishAnalyzer())
      }
    case "galician" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new GalicianAnalyzer(stopwords))
        case None =>
          Some(new GalicianAnalyzer())
      }
    case "hindi" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new HindiAnalyzer(stopwords))
        case None =>
          Some(new HindiAnalyzer())
      }
    case "hungarian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new HungarianAnalyzer(stopwords))
        case None =>
          Some(new HungarianAnalyzer())
      }
    case "armenian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ArmenianAnalyzer(stopwords))
        case None =>
          Some(new ArmenianAnalyzer())
      }
    case "indonesian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new IndonesianAnalyzer(stopwords))
        case None =>
          Some(new IndonesianAnalyzer())
      }
    case "italian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ItalianAnalyzer(stopwords))
        case None =>
          Some(new ItalianAnalyzer())
      }
    case "japanese" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new JapaneseAnalyzer(null, JapaneseTokenizer.DEFAULT_MODE, stopwords, JapaneseAnalyzer.getDefaultStopTags))
        case None =>
          Some(new JapaneseAnalyzer())
      }
    case "latvian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new LatvianAnalyzer(stopwords))
        case None =>
          Some(new LatvianAnalyzer())
      }
    case "dutch" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new DutchAnalyzer(stopwords))
        case None =>
          Some(new DutchAnalyzer())
      }
    case "norwegian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new NorwegianAnalyzer(stopwords))
        case None =>
          Some(new NorwegianAnalyzer())
      }
    case "polish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new PolishAnalyzer(stopwords))
        case None =>
          Some(new PolishAnalyzer())
      }
    case "portuguese" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new PortugueseAnalyzer(stopwords))
        case None =>
          Some(new PortugueseAnalyzer())
      }
    case "romanian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new RomanianAnalyzer(stopwords))
        case None =>
          Some(new RomanianAnalyzer())
      }
    case "russian" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new RussianAnalyzer(stopwords))
        case None =>
          Some(new RussianAnalyzer())
      }
    case "classic" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ClassicAnalyzer(stopwords))
        case None =>
          Some(new ClassicAnalyzer())
      }
    case "standard" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new StandardAnalyzer(stopwords))
        case None =>
          Some(new StandardAnalyzer())
      }
    case "email" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new UAX29URLEmailAnalyzer(stopwords))
        case None =>
          Some(new UAX29URLEmailAnalyzer())
      }
    case "perfield" =>
      val fallbackAnalyzer = new StandardAnalyzer()
      val defaultAnalyzer: Analyzer = options.get("default").flatMap(parseDefault) match {
        case Some(defaultOptions) =>
          createAnalyzerInt(defaultOptions) match {
            case Some(defaultAnalyzer1) =>
              defaultAnalyzer1
            case None =>
              fallbackAnalyzer
          }
        case None =>
          fallbackAnalyzer
      }

      def parseFields(fields: List[_]): List[(String, Option[Map[String, Any]])] =
        // anaylyzerName can be a String or a single String element wrapped in a List
        // the latter is a corner case which we should deprecate
        fields.collect {
          case (field: String, analyzerName: String) => (field, Some(Map("name" -> analyzerName)))
          case (field: String, List(analyzerName: String)) => (field, Some(Map("name" -> analyzerName)))
          case (field: String, _) => (field, None)
        }

      var fieldMap: Map[String, Analyzer] = options.get("fields") match {
        case Some(fields: List[_]) =>
          parseFields(fields).map {
            case (field, Some(options)) =>
              createAnalyzerInt(options) match {
                case Some(fieldAnalyzer) =>
                  (field, fieldAnalyzer)
                case None =>
                  (field, defaultAnalyzer)
              }
            case (field, None) =>
              (field, defaultAnalyzer)
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
          Some(new SwedishAnalyzer(stopwords))
        case None =>
          Some(new SwedishAnalyzer())
      }
    case "thai" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new ThaiAnalyzer(stopwords))
        case None =>
          Some(new ThaiAnalyzer())
      }
    case "turkish" =>
      getStopWords(options) match {
        case Some(stopwords) =>
          Some(new TurkishAnalyzer(stopwords))
        case None =>
          Some(new TurkishAnalyzer())
      }
    case _ =>
      None
  }

  def parseDefault(default: Any): Option[Map[String, Any]] = default match {
    case list: List[_] => Some(AnalyzerOptions.fromKVsList(list).toMap)
    case string: String => Some(AnalyzerOptions.fromAnalyzerName(string).toMap)
    case _ => None
  }

  def getStopWords(options: Map[String, Any]): Option[CharArraySet] =
    options.get("stopwords").collect { case list: List[_] => list.collect { case word: String => word } }

  implicit def listToJavaSet(list: List[String]): JSet[String] = {
    (Set() ++ list).asJava
  }

  implicit def listToCharArraySet(list: List[String]): CharArraySet = {
    CharArraySet.unmodifiableSet(CharArraySet.copy((Set() ++ list).asJava))
  }

}
