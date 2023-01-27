package com.cloudant.zio.actors

import Codec._

trait TypeFactory {
  def createType(name: Symbol, arity: Int, reader: Any): Option[ClouseauMessage]
  def parse(term: ETerm): Option[ClouseauMessage]
}

class TermReader // we could just have a reference to mailbox here
// but we should not remove abstraction

sealed trait ClouseauMessage
case class CleanupDbMsg(dbName: String, activeSigs: List[String]) extends ClouseauMessage
case class CleanupPathMsg(path: String)                           extends ClouseauMessage
case class CloseLRUByPathMsg(path: String)                        extends ClouseauMessage
case class CommitMsg(seq: Long)                                   extends ClouseauMessage
case class DeleteDocMsg(id: String)                               extends ClouseauMessage
case class DiskSizeMsg(path: String)                              extends ClouseauMessage
case class Group1Msg(query: String, field: String, refresh: Boolean, groupSort: Any, groupOffset: Int, groupLimit: Int)
    extends ClouseauMessage
case class Group2Msg(options: Map[Symbol, Any])                 extends ClouseauMessage
case class OpenIndexMsg(peer: EPid, path: String, options: Any) extends ClouseauMessage
case class RenamePathMsg(dbName: String)                        extends ClouseauMessage
case class SearchRequest(options: Map[Symbol, Any])             extends ClouseauMessage
case class SetPurgeSeqMsg(seq: Long)                            extends ClouseauMessage
case class SetUpdateSeqMsg(seq: Long)                           extends ClouseauMessage

object ClouseauTypeFactory extends TypeFactory {
  def createType(name: Symbol, arity: Int, _reader: Any): Option[ClouseauMessage] =
    (name, arity) match {
      case (Symbol("open"), 4) => {
        // I'll keep this comment for now to remind myself how it is done in original Scalang
        // Some(OpenIndexMsg(reader.readAs[Pid], reader.readAs[String], reader.readTerm))
        None // FIXME by properly constructing OpenIndexMsg message
      }
    }

  def parse(term: ETerm): Option[ClouseauMessage] =
    term match {
      case ETuple(List(EAtom(Symbol("cleanup")), EString(dbName), EList(activeSigs))) =>
        Some(CleanupDbMsg(dbName, activeSigs.map(_.asInstanceOf[EString].str)))
      case ETuple(List(EAtom(Symbol("cleanup")), EString(path))) =>
        Some(CleanupPathMsg(path))
      case ETuple(List(EAtom(Symbol("close_lru_by_path")), EString(path))) =>
        Some(CloseLRUByPathMsg(path))
      case ETuple(List(EAtom(Symbol("commit")), ELong(seq))) =>
        Some(CommitMsg(seq.toLong))
      case ETuple(List(EAtom(Symbol("delete")), EString(id))) =>
        Some(DeleteDocMsg(id))
      case ETuple(List(EAtom(Symbol("disk_size")), EString(path))) =>
        Some(DiskSizeMsg(path))
      case ETuple(
            List(
              EAtom(Symbol("group1")),
              EString(query),
              EString(field),
              EBoolean(refresh),
              groupSort,
              EInt(groupOffset),
              EInt(groupLimit)
            )
          ) =>
        Some(Group1Msg(query, field, refresh, groupSort, groupOffset, groupLimit))
      case ETuple(List(EAtom(Symbol("group2")), EMap(options))) =>
        Some(Group2Msg(options.foldLeft(Map.empty[Symbol, Any]) { case (map, (k, v)) =>
          map + (k.asInstanceOf[EAtom].atom -> getValue(v))
        }))
      case ETuple(List(EAtom(Symbol("open")), peer, EString(path), options)) =>
        Some(OpenIndexMsg(peer.asInstanceOf[EPid], path, options))
      case ETuple(List(EAtom(Symbol("rename")), EString(dbName))) =>
        Some(RenamePathMsg(dbName))
      case ETuple(List(EAtom(Symbol("search")), EMap(options))) =>
        Some(SearchRequest(options.foldLeft(Map.empty[Symbol, Any]) { case (map, (k, v)) =>
          map + (k.asInstanceOf[EAtom].atom -> getValue(v))
        }))
      case ETuple(List(EAtom(Symbol("set_purge_seq")), ELong(seq))) =>
        Some(SetPurgeSeqMsg(seq.toLong))
      case ETuple(List(EAtom(Symbol("set_update_seq")), ELong(seq))) =>
        Some(SetUpdateSeqMsg(seq.toLong))
      case _ => None
    }
}
