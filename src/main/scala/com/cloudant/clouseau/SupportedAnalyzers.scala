package com.cloudant.clouseau

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.HashSet
import java.util.{Set => JSet}
import org.apache.log4j.Logger
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.StopwordAnalyzerBase
import org.apache.lucene.util.Version
import scala.collection.JavaConversions._

import org.apache.lucene.analysis.KeywordAnalyzer
import org.apache.lucene.analysis.SimpleAnalyzer
import org.apache.lucene.analysis.WhitespaceAnalyzer
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
import org.apache.lucene.analysis.sv.SwedishAnalyzer
import org.apache.lucene.analysis.th.ThaiAnalyzer
import org.apache.lucene.analysis.tr.TurkishAnalyzer

// Extras
import org.apache.lucene.analysis.ja.JapaneseTokenizer

object SupportedAnalyzers {

  val version = Version.LUCENE_36
  val logger = Logger.getLogger("clouseau.analyzers")

  def createAnalyzer(options : Map[String, Any]) : Option[Analyzer] = {
    options.get("name") match {
      case Some(name : String) =>
        createAnalyzer(name, options)
      case None =>
        None
    }
  }

  def createAnalyzer(name : String, options : Map[String, Any]) : Option[Analyzer] = name match {
    case "keyword" =>
      Some(new KeywordAnalyzer())
    case "simple" =>
      Some(new SimpleAnalyzer(version))
    case "whitespace" =>
      Some(new WhitespaceAnalyzer(version))
    case "arabic" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new ArabicAnalyzer(version, stopwords))
        case _ =>
          Some(new ArabicAnalyzer(version))
      }
    case "bulgarian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new BulgarianAnalyzer(version, stopwords))
        case _ =>
          Some(new BulgarianAnalyzer(version))
      }
    case "brazilian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new BrazilianAnalyzer(version, stopwords))
        case _ =>
          Some(new BrazilianAnalyzer(version))
      }
    case "catalan" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new CatalanAnalyzer(version, stopwords))
        case _ =>
          Some(new CatalanAnalyzer(version))
      }
    case "cjk" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new CJKAnalyzer(version, stopwords))
        case _ =>
          Some(new CJKAnalyzer(version))
      }
    case "chinese" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new SmartChineseAnalyzer(version, stopwords))
        case _ =>
          Some(new SmartChineseAnalyzer(version))
      }
    case "czech" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new CzechAnalyzer(version, stopwords))
        case _ =>
          Some(new CzechAnalyzer(version))
      }
    case "danish" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new DanishAnalyzer(version, stopwords))
        case _ =>
          Some(new DanishAnalyzer(version))
      }
    case "german" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new GermanAnalyzer(version, stopwords))
        case _ =>
          Some(new GermanAnalyzer(version))
      }
    case "greek" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new GreekAnalyzer(version, stopwords))
        case _ =>
          Some(new GreekAnalyzer(version))
      }
    case "english" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new EnglishAnalyzer(version, stopwords))
        case _ =>
          Some(new EnglishAnalyzer(version))
      }
    case "spanish" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new SpanishAnalyzer(version, stopwords))
        case _ =>
          Some(new SpanishAnalyzer(version))
      }
    case "basque" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new BasqueAnalyzer(version, stopwords))
        case _ =>
          Some(new BasqueAnalyzer(version))
      }
    case "persian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new PersianAnalyzer(version, stopwords))
        case _ =>
          Some(new PersianAnalyzer(version))
      }
    case "finnish" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new FinnishAnalyzer(version, stopwords))
        case _ =>
          Some(new FinnishAnalyzer(version))
      }
    case "irish" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new IrishAnalyzer(version, CharArraySet.unmodifiableSet(CharArraySet.copy(version, stopwords))))
        case _ =>
          Some(new IrishAnalyzer(version))
      }
    case "galician" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new GalicianAnalyzer(version, stopwords))
        case _ =>
          Some(new GalicianAnalyzer(version))
      }
    case "hindi" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new HindiAnalyzer(version, stopwords))
        case _ =>
          Some(new HindiAnalyzer(version))
      }
    case "hungarian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new HungarianAnalyzer(version, stopwords))
        case _ =>
          Some(new HungarianAnalyzer(version))
      }
    case "indonesian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new IndonesianAnalyzer(version, stopwords))
        case _ =>
          Some(new IndonesianAnalyzer(version))
      }
    case "italian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new ItalianAnalyzer(version, stopwords))
        case _ =>
          Some(new ItalianAnalyzer(version))
      }
    case "japanese" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new JapaneseAnalyzer(version, null, JapaneseTokenizer.DEFAULT_MODE,
                                    CharArraySet.unmodifiableSet(CharArraySet.copy(version, stopwords)),
                                    new HashSet[String]()))
        case _ =>
          Some(new JapaneseAnalyzer(version))
      }
    case "latvian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new LatvianAnalyzer(version, stopwords))
        case _ =>
          Some(new LatvianAnalyzer(version))
      }
    case "dutch" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new DutchAnalyzer(version, stopwords))
        case _ =>
          Some(new DutchAnalyzer(version))
      }
    case "norwegian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new NorwegianAnalyzer(version, stopwords))
        case _ =>
          Some(new NorwegianAnalyzer(version))
      }
    case "polish" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new PolishAnalyzer(version, stopwords))
        case _ =>
          Some(new PolishAnalyzer(version))
      }
    case "portuguese" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new PortugueseAnalyzer(version, stopwords))
        case _ =>
          Some(new PortugueseAnalyzer(version))
      }
    case "romanian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new RomanianAnalyzer(version, stopwords))
        case _ =>
          Some(new RomanianAnalyzer(version))
      }
    case "russian" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new RussianAnalyzer(version, stopwords))
        case _ =>
          Some(new RussianAnalyzer(version))
      }
    case "classic" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new ClassicAnalyzer(version, stopwords))
        case _ =>
          Some(new ClassicAnalyzer(version))
      }
    case "standard" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new StandardAnalyzer(version, stopwords))
        case _ =>
          Some(new StandardAnalyzer(version))
      }
    case "swedish" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new SwedishAnalyzer(version, stopwords))
        case _ =>
          Some(new SwedishAnalyzer(version))
      }
    case "thai" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new ThaiAnalyzer(version, stopwords))
        case _ =>
          Some(new ThaiAnalyzer(version))
      }
    case "turkish" =>
      options.get("stopwords") match {
        case Some(stopwords : List[ByteBuffer]) =>
          Some(new TurkishAnalyzer(version, stopwords))
        case _ =>
          Some(new TurkishAnalyzer(version))
      }
    case _ =>
      None
  }

  implicit def listToJavaSet(list : List[ByteBuffer]) : JSet[ByteBuffer] = {
    Set() ++ list
  }

}
