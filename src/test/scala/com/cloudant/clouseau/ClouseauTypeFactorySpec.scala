package com.cloudant.clouseau

import org.specs2.mutable.SpecificationWithJUnit
import org.apache.lucene.document.Field._

class ClouseauTypeFactorySpec extends SpecificationWithJUnit {

  "the type factory" should {

    import ClouseauTypeFactory._

    "support true for store" in {
      toStore(Map("store" -> true)) must be equalTo Store.YES
    }
    "support false for store" in {
      toStore(Map("store" -> false)) must be equalTo Store.NO
    }

    "support all enumeration values for store" in {
      for (store <- Store.values) {
        (toStore(Map("store" -> store.name)) must be equalTo
          Store.valueOf(store.name))
      }
    }

    "support all enumeration values for store (case insensitively)" in {
      for (store <- Store.values) {
        (toStore(Map("store" -> store.name.toLowerCase)) must be equalTo
          Store.valueOf(store.name))
      }
    }

    "support true for index" in {
      toIndex(Map("index" -> true)) must be equalTo Index.ANALYZED
    }

    "support false for index" in {
      toIndex(Map("index" -> false)) must be equalTo Index.NO
    }

    "support all enumeration values for index" in {
      for (index <- Index.values) {
        (toIndex(Map("index" -> index.name)) must be equalTo
          Index.valueOf(index.name))
      }
    }

    "support all enumeration values for index (case insensitively)" in {
      for (index <- Index.values) {
        (toIndex(Map("index" -> index.name.toLowerCase)) must be equalTo
          Index.valueOf(index.name))
      }
    }

  }

}