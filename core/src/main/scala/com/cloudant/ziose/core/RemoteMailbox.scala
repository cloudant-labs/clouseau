package com.cloudant.ziose.core

trait RemoteMailbox {
  val id: Codec.EPid

  def create(name: String): RemoteMailbox
  def name(): String // TODO: Consider switching to Symbol
}
