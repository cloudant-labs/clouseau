package com.cloudant.clouseau

import org.apache.commons.configuration.SystemConfiguration
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope

class AnalyzerServiceSpec extends SpecificationWithJUnit {
  "an analyzer" should {

    "demonstrate standard tokenization" in new analyzer_service {
      service.handleCall(null, ('analyze, "standard", "foo bar baz")) must be equalTo (('ok, List("foo", "bar", "baz")))
    }

    "demonstrate keyword tokenization" in new analyzer_service {
      service.handleCall(null, ('analyze, "keyword", "foo bar baz")) must be equalTo (('ok, List("foo bar baz")))
    }
  }
}

trait analyzer_service extends Scope {
  val config = new SystemConfiguration()
  val args = new ConfigurationArgs(config)
  val service = new AnalyzerService(new FakeServiceContext[ConfigurationArgs](args))
}

