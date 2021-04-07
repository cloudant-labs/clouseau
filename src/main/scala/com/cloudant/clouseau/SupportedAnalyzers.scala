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
import org.apache.log4j.Logger
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

  val logger = Logger.getLogger("clouseau.analyzers")

  def createAnalyzer(options: Any): Option[Analyzer] = {
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

  def createAnalyzerInt(options: Any): Option[Analyzer] = options match {
    case name: String =>
      createAnalyzerInt(Map("name" -> name))
    case list: List[(String, Any)] =>
      try {
        createAnalyzerInt(list.toMap)
      } catch {
        case e: ClassCastException => None
      }
    case map: Map[String, Any] =>
      map.get("name") match {
        case Some(name: String) =>
          createAnalyzerInt(name, map)
        case None =>
          None
      }
    case _ =>
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
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new ArabicAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new ArabicAnalyzer(IndexService.version))
      }
    case "bulgarian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new BulgarianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new BulgarianAnalyzer(IndexService.version))
      }
    case "brazilian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new BrazilianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new BrazilianAnalyzer(IndexService.version))
      }
    case "catalan" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new CatalanAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new CatalanAnalyzer(IndexService.version))
      }
    case "cjk" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new CJKAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new CJKAnalyzer(IndexService.version))
      }
    case "chinese" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new SmartChineseAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new SmartChineseAnalyzer(IndexService.version))
      }
    case "czech" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new CzechAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new CzechAnalyzer(IndexService.version))
      }
    case "danish" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new DanishAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new DanishAnalyzer(IndexService.version))
      }
    case "german" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new GermanAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new GermanAnalyzer(IndexService.version))
      }
    case "greek" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new GreekAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new GreekAnalyzer(IndexService.version))
      }
    case "english" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new EnglishAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new EnglishAnalyzer(IndexService.version))
      }
    case "spanish" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new SpanishAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new SpanishAnalyzer(IndexService.version))
      }
    case "basque" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new BasqueAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new BasqueAnalyzer(IndexService.version))
      }
    case "persian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new PersianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new PersianAnalyzer(IndexService.version))
      }
    case "finnish" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new FinnishAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new FinnishAnalyzer(IndexService.version))
      }
    case "french" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new FrenchAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new FrenchAnalyzer(IndexService.version))
      }
    case "irish" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new IrishAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new IrishAnalyzer(IndexService.version))
      }
    case "galician" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new GalicianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new GalicianAnalyzer(IndexService.version))
      }
    case "hindi" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new HindiAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new HindiAnalyzer(IndexService.version))
      }
    case "hungarian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new HungarianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new HungarianAnalyzer(IndexService.version))
      }
    case "armenian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new ArmenianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new ArmenianAnalyzer(IndexService.version))
      }
    case "indonesian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new IndonesianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new IndonesianAnalyzer(IndexService.version))
      }
    case "italian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new ItalianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new ItalianAnalyzer(IndexService.version))
      }
    case "japanese" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new JapaneseAnalyzer(IndexService.version, null, JapaneseTokenizer.DEFAULT_MODE, stopwords, JapaneseAnalyzer.getDefaultStopTags))
        case _ =>
          Some(new JapaneseAnalyzer(IndexService.version))
      }
    case "latvian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new LatvianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new LatvianAnalyzer(IndexService.version))
      }
    case "dutch" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new DutchAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new DutchAnalyzer(IndexService.version))
      }
    case "norwegian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new NorwegianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new NorwegianAnalyzer(IndexService.version))
      }
    case "polish" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new PolishAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new PolishAnalyzer(IndexService.version))
      }
    case "portuguese" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new PortugueseAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new PortugueseAnalyzer(IndexService.version))
      }
    case "romanian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new RomanianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new RomanianAnalyzer(IndexService.version))
      }
    case "russian" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new RussianAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new RussianAnalyzer(IndexService.version))
      }
    case "classic" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new ClassicAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new ClassicAnalyzer(IndexService.version))
      }
    case "standard" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new StandardAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new StandardAnalyzer(IndexService.version))
      }
    case "email" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new UAX29URLEmailAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new UAX29URLEmailAnalyzer(IndexService.version))
      }
    case "perfield" =>
      val fallbackAnalyzer = new StandardAnalyzer(IndexService.version)
      val defaultAnalyzer: Analyzer = options.get("default") match {
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
      var fieldMap: Map[String, Analyzer] = options.get("fields") match {
        case Some(fields: List[(String, Any)]) =>
          fields map { kv =>
            createAnalyzerInt(kv._2) match {
              case Some(fieldAnalyzer) =>
                (kv._1, fieldAnalyzer)
              case None =>
                (kv._1, defaultAnalyzer)
            }
          } toMap
        case _ =>
          Map.empty
      }
      fieldMap += ("_id" -> new KeywordAnalyzer())
      fieldMap += ("_partition" -> new KeywordAnalyzer())
      Some(new PerFieldAnalyzer(defaultAnalyzer, fieldMap))
    case "swedish" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new SwedishAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new SwedishAnalyzer(IndexService.version))
      }
    case "thai" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new ThaiAnalyzer(IndexService.version, stopwords))
        case _ =>
          Some(new ThaiAnalyzer(IndexService.version))
      }
    case "turkish" =>
      options.get("stopwords") match {
        case Some(stopwords: List[String]) =>
          Some(new TurkishAnalyzer(IndexService.version, stopwords))
        case _ =>
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

}
