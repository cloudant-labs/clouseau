package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.da.DanishAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.es.SpanishAnalyzer;
import org.apache.lucene.analysis.eu.BasqueAnalyzer;
import org.apache.lucene.analysis.fa.PersianAnalyzer;
import org.apache.lucene.analysis.fi.FinnishAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.ga.IrishAnalyzer;
import org.apache.lucene.analysis.gl.GalicianAnalyzer;
import org.apache.lucene.analysis.hi.HindiAnalyzer;
import org.apache.lucene.analysis.hu.HungarianAnalyzer;
import org.apache.lucene.analysis.hy.ArmenianAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseTokenizer;
import org.apache.lucene.analysis.lv.LatvianAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.pl.PolishAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.UAX29URLEmailAnalyzer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;

import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class SupportedAnalyzers {

    public static Analyzer createAnalyzer(final OtpErlangObject analyzerConfig) {
        final Analyzer analyzer = createAnalyzerInt(analyzerConfig);

        if (analyzer == null) {
            return null;
        }

        if (analyzer instanceof PerFieldAnalyzer) {
            return analyzer;
        }

        return new PerFieldAnalyzer(analyzer,
                Map.of("_id", new KeywordAnalyzer(), "_partition", new KeywordAnalyzer()));
    }

    private static Analyzer createAnalyzerInt(final Object analyzerConfig) {

        if (analyzerConfig instanceof OtpErlangBinary) {
            return createAnalyzerInt(Map.of(asBinary("name"), analyzerConfig));
        }

        if (analyzerConfig instanceof OtpErlangTuple) {
            return createAnalyzerInt(asMap((OtpErlangTuple) analyzerConfig));
        }

        if (analyzerConfig instanceof Map) {
            @SuppressWarnings("rawtypes")
            final Map options = (Map) analyzerConfig;
            final String name = asString((OtpErlangBinary) options.get(asBinary("name")));
            final CharArraySet stopwords;
            if (options.containsKey("stopwords")) {
                final Set<String> set = new HashSet<String>();
                for (final OtpErlangObject item : ((OtpErlangList) options.get(asBinary("stopwords")))) {
                    set.add(asString((OtpErlangBinary) item));
                }
                stopwords = new CharArraySet(LuceneUtils.VERSION, set, false);
            } else {
                stopwords = null;
            }

            switch (name) {
            case "keyword":
                return new KeywordAnalyzer();
            case "simple":
                return new SimpleAnalyzer(LuceneUtils.VERSION);
            case "whitespace":
                return new WhitespaceAnalyzer(LuceneUtils.VERSION);
            case "arabic":
                if (stopwords != null) {
                    return new ArabicAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new ArabicAnalyzer(LuceneUtils.VERSION);
                }
            case "bulgarian":
                if (stopwords != null) {
                    return new BulgarianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new BulgarianAnalyzer(LuceneUtils.VERSION);
                }
            case "brazilian":
                if (stopwords != null) {
                    return new BrazilianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new BrazilianAnalyzer(LuceneUtils.VERSION);
                }
            case "catalan":
                if (stopwords != null) {
                    return new CatalanAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new CatalanAnalyzer(LuceneUtils.VERSION);
                }
            case "cjk":
                if (stopwords != null) {
                    return new CJKAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new CJKAnalyzer(LuceneUtils.VERSION);
                }
            case "chinese":
                if (stopwords != null) {
                    return new SmartChineseAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new SmartChineseAnalyzer(LuceneUtils.VERSION);
                }
            case "czech":
                if (stopwords != null) {
                    return new CzechAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new CzechAnalyzer(LuceneUtils.VERSION);
                }
            case "danish":
                if (stopwords != null) {
                    return new DanishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new DanishAnalyzer(LuceneUtils.VERSION);
                }
            case "german":
                if (stopwords != null) {
                    return new GermanAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new GermanAnalyzer(LuceneUtils.VERSION);
                }
            case "greek":
                if (stopwords != null) {
                    return new GreekAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new GreekAnalyzer(LuceneUtils.VERSION);
                }
            case "english":
                if (stopwords != null) {
                    return new EnglishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new EnglishAnalyzer(LuceneUtils.VERSION);
                }
            case "spanish":
                if (stopwords != null) {
                    return new SpanishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new SpanishAnalyzer(LuceneUtils.VERSION);
                }
            case "basque":
                if (stopwords != null) {
                    return new BasqueAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new BasqueAnalyzer(LuceneUtils.VERSION);
                }
            case "persian":
                if (stopwords != null) {
                    return new PersianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new PersianAnalyzer(LuceneUtils.VERSION);
                }
            case "finnish":
                if (stopwords != null) {
                    return new FinnishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new FinnishAnalyzer(LuceneUtils.VERSION);
                }
            case "french":
                if (stopwords != null) {
                    return new FrenchAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new FrenchAnalyzer(LuceneUtils.VERSION);
                }
            case "irish":
                if (stopwords != null) {
                    return new IrishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new IrishAnalyzer(LuceneUtils.VERSION);
                }
            case "galician":
                if (stopwords != null) {
                    return new GalicianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new GalicianAnalyzer(LuceneUtils.VERSION);
                }
            case "hindi":
                if (stopwords != null) {
                    return new HindiAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new HindiAnalyzer(LuceneUtils.VERSION);
                }
            case "hungarian":
                if (stopwords != null) {
                    return new HungarianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new HungarianAnalyzer(LuceneUtils.VERSION);
                }
            case "armenian":
                if (stopwords != null) {
                    return new ArmenianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new ArmenianAnalyzer(LuceneUtils.VERSION);
                }
            case "italian":
                if (stopwords != null) {
                    return new ItalianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new ItalianAnalyzer(LuceneUtils.VERSION);
                }
            case "japanese":
                if (stopwords != null) {
                    return new JapaneseAnalyzer(LuceneUtils.VERSION, null, JapaneseTokenizer.DEFAULT_MODE, stopwords,
                            JapaneseAnalyzer.getDefaultStopTags());
                } else {
                    return new JapaneseAnalyzer(LuceneUtils.VERSION);
                }
            case "latvian":
                if (stopwords != null) {
                    return new LatvianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new LatvianAnalyzer(LuceneUtils.VERSION);
                }
            case "dutch":
                if (stopwords != null) {
                    return new DutchAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new DutchAnalyzer(LuceneUtils.VERSION);
                }
            case "norwegian":
                if (stopwords != null) {
                    return new NorwegianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new NorwegianAnalyzer(LuceneUtils.VERSION);
                }
            case "polish":
                if (stopwords != null) {
                    return new PolishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new PolishAnalyzer(LuceneUtils.VERSION);
                }
            case "portuguese":
                if (stopwords != null) {
                    return new PortugueseAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new PortugueseAnalyzer(LuceneUtils.VERSION);
                }
            case "romanian":
                if (stopwords != null) {
                    return new RomanianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new RomanianAnalyzer(LuceneUtils.VERSION);
                }
            case "russian":
                if (stopwords != null) {
                    return new RussianAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new RussianAnalyzer(LuceneUtils.VERSION);
                }
            case "classic":
                if (stopwords != null) {
                    return new ClassicAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new ClassicAnalyzer(LuceneUtils.VERSION);
                }
            case "standard":
                if (stopwords != null) {
                    return new StandardAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new StandardAnalyzer(LuceneUtils.VERSION);
                }
            case "email":
                if (stopwords != null) {
                    return new UAX29URLEmailAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new UAX29URLEmailAnalyzer(LuceneUtils.VERSION);
                }
            case "perfield": {
                final Analyzer defaultAnalyzer;
                if (options.containsKey(asBinary("default"))) {
                    defaultAnalyzer = createAnalyzerInt(options.get(asBinary("default")));
                } else {
                    defaultAnalyzer = new StandardAnalyzer(LuceneUtils.VERSION);
                }
                final Map<String, Analyzer> fieldMap;
                if (options.containsKey(asBinary("fields"))) {
                    fieldMap = new HashMap<String, Analyzer>();
                    final OtpErlangList fields = (OtpErlangList) ((OtpErlangTuple) options.get(asBinary("fields")))
                            .elementAt(0);
                    for (OtpErlangObject o : fields) {
                        final OtpErlangTuple t = (OtpErlangTuple) o;
                        final String fieldName = asString(t.elementAt(0));
                        final Analyzer fieldAnalyzer = createAnalyzerInt(t.elementAt(1));
                        fieldMap.put(fieldName, fieldAnalyzer != null ? fieldAnalyzer : defaultAnalyzer);
                    }
                } else {
                    fieldMap = Collections.emptyMap();
                }
                fieldMap.put("_id", new KeywordAnalyzer());
                fieldMap.put("_partition", new KeywordAnalyzer());
                return new PerFieldAnalyzer(defaultAnalyzer, fieldMap);
            }
            case "swedish":
                if (stopwords != null) {
                    return new SwedishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new SwedishAnalyzer(LuceneUtils.VERSION);
                }
            case "thai":
                if (stopwords != null) {
                    return new ThaiAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new ThaiAnalyzer(LuceneUtils.VERSION);
                }
            case "turkish":
                if (stopwords != null) {
                    return new TurkishAnalyzer(LuceneUtils.VERSION, stopwords);
                } else {
                    return new TurkishAnalyzer(LuceneUtils.VERSION);
                }
            default:
                return null;
            }
        }

        return null;
    }

}