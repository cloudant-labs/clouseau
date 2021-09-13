package com.cloudant.clouseau.node

import com.ericsson.otp.erlang._;

class ClouseauNode(name: String, cookie: String) {

  var node = new OtpNode(name, cookie)

  node.createMbox()

  def setup() {
  }

}
