package com.cloudant.ziose.clouseau

/**
 * The only purpose of this service is to have registered `rex` process to answer `ping` requests. This is a requirement
 * imposed by the `erl_call` tool. The `ping` requests are handled by the framework. So we don't need to handle any
 * events ourselves. In addition to this important role we use `rex` to handle control requests to control the
 * `Clouseau` instance.
 *
 * ```erlang
 * ❯ erl -setcookie cookie -name node1@127.0.0.1
 * Erlang/OTP 26 [erts-14.2.5.10] [source] [64-bit] [smp:10:10] [ds:10:10:10] [async-threads:1] [jit]
 * Eshell V14.2.5.10 (press Ctrl+G to abort, type help(). for help)
 * (eshell@127.0.0.1)2> net_adm:ping('clouseau1@127.0.0.1').
 * pong
 * (eshell@127.0.0.1)3> gen_server:call({rex, 'clouseau1@127.0.0.1'}, {top, message_queue_len}).
 * {ok,[
 *     {process_info,#{message_queue_len => 0,name => analyzer,pid => <14713.1.0>, tags => []}},
 *     {process_info,#{message_queue_len => 0,name => cleanup,pid => <14713.2.0>, tags => []}},
 *     {process_info,#{message_queue_len => 0,name => init,pid => <14713.3.0>, tags => []}},
 *     {process_info,#{message_queue_len => 0,name => main,pid => <14713.4.0>, tags => []}},
 *     {process_info,#{message_queue_len => 0,name => rex,pid => <14713.5.0>, tags => []}},
 *     {process_info,#{message_queue_len => 0,name => sup,pid => <14713.6.0>, tags => []}},
 *     {process_info,#{message_queue_len => 12345,name => none,pid => <14713.7.0>, tags => [<<"shards/00000000-ffffffff/foo.1730151232">>]}}
 * ]}
 * ```
 *
 * ```shell
 * ❯ erl_call -c <cookie>  -n 'clouseau1@127.0.0.1' -a 'clouseau top [message_queue_len]'
 * > {ok, [
 *     {process_info, #{message_queue_len => 0,name => main,pid => "<clouseau1@127.0.0.1.2.0>",tags => [], message_queue_len => 0}},
 *     {process_info, #{message_queue_len => 0,name => init, pid => "<clouseau1@127.0.0.1.5.0>",tags => [], message_queue_len => 0}},
 *     {process_info, #{message_queue_len => 0,name => analyzer, pid => "<clouseau1@127.0.0.1.4.0>",tags => [], message_queue_len => 0}},
 *     {process_info, #{message_queue_len => 0,name => rex, pid => "<clouseau1@127.0.0.1.6.0>",tags => [], message_queue_len => 0}},
 *     {process_info, #{message_queue_len => 0,name => cleanup, pid => "<clouseau1@127.0.0.1.3.0>",tags => [], message_queue_len => 0}},
 *     {process_info, #{message_queue_len => 0,name => sup, pid => "<clouseau1@127.0.0.1.1.0>", tags => [], message_queue_len => 0}},
 *     {process_info, #{message_queue_len => 12345,name => none, pid => "<clouseau1@127.0.0.1.7.0>"", tags => ["shards/00000000-ffffffff/foo.1730151232"]}},
 * ]}
 * ```
 */

import com.cloudant.ziose.macros.CheckEnv
import com.cloudant.ziose.{core, scalang}
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, ProcessContext}
import core.Codec._
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}

class RexService(ctx: ServiceContext[None.type])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  var ctrl: Option[ClouseauControl[_]] = None

  override def handleInit(): Unit = {
    ctrl = Some(new ClouseauControl(adapter.ctx.asInstanceOf[ProcessContext].worker, ClouseauTypeFactory))
  }

  override def handleCall(tag: (Pid, Any), msg: Any): Any = {
    def reply(result: Either[ETerm, ETerm]) = result match {
      case Right(reply) =>
        (Symbol("reply"), (Symbol("ok"), reply))
      case Left(error) =>
        (Symbol("reply"), (Symbol("error"), error))
    }

    msg match {
      // gen_server:call({rex, 'clouseau1@127.0.0.1'}, {service, list})
      case (Symbol("service"), Symbol("list")) =>
        ctrl.get.listServices() match {
          case Right(term) => reply(Right(fromScala(term)))
          case Left(error) => reply(Left(error.asETerm))
        }
      // gen_server:call({rex, 'clouseau1@127.0.0.1'}, {top, message_queue_len})
      case (Symbol("top"), key: Symbol) =>
        ctrl.get.handleTop(List(key)) match {
          case Right(term) => reply(Right(fromScala(term.map(_.asETerm))))
          case Left(error) => reply(Left(error.asETerm))
        }
      // gen_server:call({rex, 'clouseau1@127.0.0.1'}, {topMeters, mailbox, internal})
      case (Symbol("topMeters"), klass: Symbol, meterName: Symbol) =>
        ctrl.get.handleTopMeters(List((klass, meterName))) match {
          case Right(term) => reply(Right(fromScala(term.map(_.asETerm))))
          case Left(error) => reply(Left(error.asETerm))
        }
    }
  }

  override def handleInfo(request: Any): Any = {
    def reply(from: Pid, result: Either[ETerm, ETerm]) = result match {
      case Right(reply) =>
        send(from, (Symbol("rex"), (Symbol("ok"), reply)))
      case Left(error) =>
        send(from, (Symbol("rex"), (Symbol("error"), error)))
    }
    request match {
      // Example: `erl_call -c ${COOKIE} -n 'clouseau1@127.0.0.1' -a 'clouseau service list'`
      case (from: Pid, (Symbol("call"), Symbol("clouseau"), Symbol("service"), Symbol("list"), Symbol("user"))) =>
        ctrl.get.listServices() match {
          case Right(term) =>
            reply(
              from,
              Right(fromScala(term.map {
                case (k, Symbol("undefined")) =>
                  (k, EAtom("undefined"))
                case (k, pid: Pid) =>
                  (k, EString(pid.toErlangString))
              }))
            )
          case Left(error) =>
            reply(from, Left(error.asETerm))
        }

      // Example: `erl_call -c ${COOKIE} -n 'clouseau1@127.0.0.1' -a 'clouseau top [queue_length]'`
      case (from: Pid, (Symbol("call"), Symbol("clouseau"), Symbol("top"), args: List[_], Symbol("user"))) =>
        ctrl.get.handleTop(args) match {
          case Right(term) =>
            reply(from, Right(fromScala(term.map(_.asPrettyPrintedETerm))))
          case Left(error) => reply(from, Left(error.asETerm))
        }

      // Example: `erl_call -c ${COOKIE} -n 'clouseau1@127.0.0.1' -a 'clouseau topMeters [{mailbox, internal}]'`
      case (from: Pid, (Symbol("call"), Symbol("clouseau"), Symbol("topMeters"), args: List[_], Symbol("user"))) =>
        ctrl.get.handleTopMeters(args) match {
          case Right(term) =>
            reply(from, Right(fromScala(term.map(_.asPrettyPrintedETerm))))
          case Left(error) => reply(from, Left(error.asETerm))
        }

      case (from: Pid, other) =>
        reply(from, Left(adapter.fromScala()))
    }
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"ctx=$ctx",
    s"adapter=$adapter"
  )
}

private object RexService extends ActorConstructor[RexService] {
  private def make(
    node: SNode,
    service_context: ServiceContext[None.type]
  ): ActorBuilder.Builder[RexService, State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): RexService = {
      new RexService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    ActorBuilder()
      .withName("rex")
      .withMaker(maker)
      .build(this)
  }

  def start(
    node: SNode
  )(implicit
    adapter: Adapter[_, _]
  ): Any = {
    val ctx: ServiceContext[None.type] = {
      new ServiceContext[None.type] { val args: None.type = None }
    }
    node.spawnService[RexService, None.type](make(node, ctx)) match {
      case core.Success(actor) =>
        (Symbol("ok"), Pid.toScala(actor.self.pid))
      case core.Failure(reason) => reason
    }
  }
}
