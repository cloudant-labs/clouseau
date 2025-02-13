//
// Copyright 2011, Boundary
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package scalang.node

import org.jboss.netty.channel.Channel
import scala.collection.mutable.StringBuilder

case object ConnectedMessage

case class NameMessage(flags: Long, creation: Int, name: String)

case class NameMessageV5(version: Short, flags: Int, name: String)

case class StatusMessage(status: String)

case class ChallengeMessage(flags: Long, challenge: Int, creation: Int, name: String)

case class ChallengeMessageV5(version: Short, flags: Int, challenge: Int, name: String)

case class ChallengeReplyMessage(challenge: Int, digest: Array[Byte]) {
  override def toString: String = {
    val b = new StringBuilder("ChallengeReplyMessage(")
    b ++= challenge.toString
    b ++= ", "
    b ++= digest.deep.toString
    b ++= ")"
    b.toString
  }
}

case class ChallengeAckMessage(digest: Array[Byte]) {
  override def toString: String = {
    val b = new StringBuilder("ChallengeAckMessage(")
    b ++= digest.deep.toString
    b ++= ")"
    b.toString
  }
}

case class HandshakeSucceeded(node: Symbol, channel: Channel)

case class HandshakeFailed(node: Symbol)

object DistributionFlags {
  val published = 1
  val atomCache = 2
  val extendedReferences = 4
  val distMonitor = 8
  val funTags = 0x10
  val distMonitorName = 0x20
  val hiddenAtomCache = 0x40
  val newFunTags = 0x80
  val extendedPidsPorts = 0x100
  val exportPtrTag = 0x200
  val bitBinaries = 0x400
  val newFloats = 0x800
  val smallAtomTags = 0x4000
  val utf8Atoms = 0x10000
  val mapTag = 0x20000
  val bigCreation = 0x40000
  val handshake23 = 0x1000000
  val unlinkId = 0x2000000
  val v4PidsRefs = 0x4L << 32

  val defaultV5 = extendedReferences | extendedPidsPorts |
    bitBinaries | newFloats | funTags | newFunTags |
    distMonitor | distMonitorName | smallAtomTags | utf8Atoms |
    bigCreation

  val default = defaultV5 | exportPtrTag | mapTag | handshake23 |
    unlinkId | v4PidsRefs
}

class ErlangAuthException(msg: String) extends Exception(msg)
