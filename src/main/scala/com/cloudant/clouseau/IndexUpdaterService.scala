package com.cloudant.clouseau

import scalang._

case class IndexUpdaterArgs(db: Pid)
class IndexUpdaterService(ctx: ServiceContext[IndexUpdaterArgs]) extends Service(ctx) {

}