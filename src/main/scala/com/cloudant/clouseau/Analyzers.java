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

import java.io.Reader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.analysis.LowerCaseTokenizer;
import org.apache.lucene.analysis.PorterStemFilter;
import org.apache.lucene.analysis.SimpleAnalyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.util.Version;

public enum Analyzers {

    BRAZILIAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new BrazilianAnalyzer(version);
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
    DUTCH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new DutchAnalyzer(version);
        }
    },
    ENGLISH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new StandardAnalyzer(version);
        }
    },
    FRENCH {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new FrenchAnalyzer(version);
        }
    },
    GERMAN {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new GermanAnalyzer(version);
        }
    },
    KEYWORD {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new KeywordAnalyzer();
        }
    },
    PORTER {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new PorterStemAnalyzer(version);
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
    STANDARD {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new StandardAnalyzer(version);
        }
    },
    THAI {
        @Override
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new ThaiAnalyzer(version);
        }
    },
    WHITESPACE {
        public Analyzer newAnalyzer(final Version version, final String args) {
            return new WhitespaceAnalyzer(version);
        }
    };

    private static final class PorterStemAnalyzer extends Analyzer {
        private Version version;

        private PorterStemAnalyzer(final Version version) {
            this.version = version;
        }

        @Override
        public TokenStream tokenStream(final String fieldName,
                final Reader reader) {
            return new PorterStemFilter(new LowerCaseTokenizer(version, reader));
        }
    }

    public static Analyzer getAnalyzer(final Version version, final String str) {
        final String[] parts = str.split(":", 2);
        final String name = parts[0].toUpperCase();
        final String args = parts.length == 2 ? parts[1] : null;
        return Analyzers.valueOf(name).newAnalyzer(version, args);
    }

    public abstract Analyzer newAnalyzer(final Version version,
            final String args);

}
