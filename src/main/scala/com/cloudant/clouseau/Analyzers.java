package com.cloudant.clouseau;

/**
 * Copyright 2009 Robert Newson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.analysis.SimpleAnalyzer;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
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
import org.apache.lucene.analysis.id.IndonesianAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.ja.JapaneseAnalyzer;
import org.apache.lucene.analysis.lv.LatvianAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.pl.PolishAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;
import org.apache.lucene.util.Version;

public enum Analyzers {

    ARABIC {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new ArabicAnalyzer(version);
        }
    },
    ARMENIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new ArmenianAnalyzer(version);
        }
    },
    BASQUE {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new BasqueAnalyzer(version);
        }
    },
    BRAZILIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new BrazilianAnalyzer(version);
        }
    },
    BULGARIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new BulgarianAnalyzer(version);
        }
    },
    CATALAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new CatalanAnalyzer(version);
        }
    },
    CHINESE {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new SmartChineseAnalyzer(version);
        }
    },
    CJK {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new CJKAnalyzer(version);
        }
    },
    CLASSIC {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new ClassicAnalyzer(version);
        }
    },
    CZECH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new CzechAnalyzer(version);
        }
    },
    DANISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new DanishAnalyzer(version);
        }
    },
    DUTCH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new DutchAnalyzer(version);
        }
    },
    ENGLISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new EnglishAnalyzer(version);
        }
    },
    FINNISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new FinnishAnalyzer(version);
        }
    },
    FRENCH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new FrenchAnalyzer(version);
        }
    },
    GALICIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new GalicianAnalyzer(version);
        }
    },
    GERMAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new GermanAnalyzer(version);
        }
    },
    GREEK {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new GreekAnalyzer(version);
        }
    },
    HINDI {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new HindiAnalyzer(version);
        }
    },
    HUNGARIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new HungarianAnalyzer(version);
        }
    },
    INDONESIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new IndonesianAnalyzer(version);
        }
    },
    IRISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new IrishAnalyzer(version);
        }
    },
    ITALIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new ItalianAnalyzer(version);
        }
    },
    JAPANESE {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new JapaneseAnalyzer(version);
        }
    },
    KEYWORD {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new KeywordAnalyzer();
        }
    },
    LATVIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new LatvianAnalyzer(version);
        }
    },
    NORWEGIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new NorwegianAnalyzer(version);
        }
    },
    PERSIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new PersianAnalyzer(version);
        }
    },
    POLISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new PolishAnalyzer(version);
        }
    },
    PORTUGUESE {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new PortugueseAnalyzer(version);
        }
    },
    ROMANIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new RomanianAnalyzer(version);
        }
    },
    RUSSIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new RussianAnalyzer(version);
        }
    },
    SIMPLE {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new SimpleAnalyzer(version);
        }
    },
    SPANISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new SpanishAnalyzer(version);
        }
    },
    STANDARD {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new StandardAnalyzer(version);
        }
    },
    SWEDISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new SwedishAnalyzer(version);
        }
    },
    THAI {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new ThaiAnalyzer(version);
        }
    },
    TURKISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new TurkishAnalyzer(version);
        }
    },
    WHITESPACE {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new WhitespaceAnalyzer(version);
        }
    };

    public static Analyzer getAnalyzer(final Version version, final String str) {
        final String[] parts = str.split(":", 2);
        final String name = parts[0].toUpperCase();
        final String args = parts.length == 2 ? parts[1] : null;
        try {
            return Analyzers.valueOf(name).newAnalyzer(version, args);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("No known analyzer called '"
                    + str + "'");
        }
    }

    public abstract Analyzer newAnalyzer(final Version version,
            final String args);

    static {
        final Logger logger = Logger.getLogger("clouseau.analyzers");
        logger.info("Supported analyzers: " +
                    java.util.Arrays.toString(Analyzers.values()));
    }

}
