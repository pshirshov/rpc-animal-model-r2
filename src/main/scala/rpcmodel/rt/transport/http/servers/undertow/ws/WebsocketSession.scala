package rpcmodel.rt.transport.http.servers.undertow.ws

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import io.circe._
import io.circe.parser.parse
import io.circe.syntax._
import io.undertow.server.HttpServerExchange
import io.undertow.websockets.core._
import izumi.functional.bio.{BIOAsync, BIOExit, BIORunner}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncResponse.AsyncSuccess
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncResponse}
import rpcmodel.rt.transport.http.servers.shared._
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{WsConnection, WsServerInRequestContext}

trait RuntimeErrorHandler[T] {

  def onCritical(context: RuntimeErrorHandler.Context, value: List[Throwable]): Unit = {}

  def onDomain(context: RuntimeErrorHandler.Context, value: T): Unit = {}

  final def handle(context: RuntimeErrorHandler.Context)(f: BIOExit[T, _]): Unit = {
    f match {
      case BIOExit.Success(_) =>
      case failure: BIOExit.Failure[_] =>
        failure.asInstanceOf[BIOExit.Failure[T]].toEither match {
          case Left(value) =>
            onCritical(context, value)
          case Right(value) =>
            onDomain(context, value)
        }
    }
  }
}

object RuntimeErrorHandler {
  def ignore[T]: RuntimeErrorHandler[T] = new RuntimeErrorHandler[T] {}

  sealed trait Context

  object Context {

    case class WebsocketServerSession(ctx: WsConnection, message: BufferedTextMessage) extends Context

    case class WebsocketClientSession() extends Context

    case class HttpRequest(exchange: HttpServerExchange) extends Context

  }

}

class WebsocketSession[F[+ _, + _] : BIOAsync : BIORunner, Meta, C, DomainErrors]
(
  ctx: WsConnection,
  override protected val serverContextProvider: ContextProvider[F, ServerTransportError, WsServerInRequestContext, C],
  override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, C, Json]],
  printer: Printer,
  handler: TransportErrorHandler[DomainErrors, WsConnection],
  sessions: SessionManager[F, Meta],
  sessionMetaProvider: SessionMetaProvider[Meta],
  errHandler: RuntimeErrorHandler[ServerTransportError.Predefined],
) extends AbstractReceiveListener with AbstractServerHandler[F, C, WsServerInRequestContext, Json] {

  import izumi.functional.bio.BIO._

  override protected def bioAsync: BIOAsync[F] = implicitly

  val id: WsSessionId = WsSessionId(UUID.randomUUID()) // TODO: random
  val pending = new ConcurrentHashMap[InvokationId, Option[PendingResponse]]()

  val meta = new AtomicReference(sessionMetaProvider.extractInitial(ctx))
  private val terminated = new AtomicBoolean(false)

  def init(): Unit = {
    sessions.register(this)
  }

  init()

  def disconnect(): F[Throwable, Unit] = {
    F.syncThrowable(ctx.channel.sendClose())
      .catchAll(_ => F.syncThrowable(ctx.channel.close()))
  }

  override def onClose(webSocketChannel: WebSocketChannel, channel: StreamSourceFrameChannel): Unit = {
    terminated.set(true)
    sessions.drop(id)
    pending.clear()
  }

  override def onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage): Unit = {
    assert(channel == this.ctx.channel)

    val result: F[ServerTransportError.Predefined, Unit] = for {
      sbody <- F.pure(message.getData)
      decoded <- F.fromEither(parse(sbody)).leftMap(f => ServerTransportError.JsonCodecError(sbody, f))
      out <- if (decoded.asObject.exists(_.toMap.contains("methodId"))) { // incoming request
        for {
          out <- dispatchRequest(channel, sbody, decoded)
            .sandbox.leftMap(_.toEither)
            .redeemPure(handler.onError(this.ctx), v => TransportResponse.Success(v))
          json = out.value.printWith(printer)
          _ <- doSend(json).leftMap(f => ServerTransportError.EnvelopeFormatError(sbody, f))
        } yield {
        }
      } else {
        for {
          envelope <- F.fromEither(decoded.as[AsyncResponse]).leftMap(f => ServerTransportError.EnvelopeFormatError(sbody, f))
          now <- now()
          _ <- F.sync {
            pending.computeIfPresent(envelope.id, (_: InvokationId, u: Option[PendingResponse]) => {
              u match {
                case None =>
                  Some(PendingResponse(envelope, now))
                case o =>
                  o // existing values will be preserved
              }
            })
          }
        } yield {
        }
      }
    } yield {
      out
    }

    if (!terminated.get()) {
      BIORunner[F].unsafeRunAsyncAsEither(result)(errHandler.handle(RuntimeErrorHandler.Context.WebsocketServerSession(ctx, message)))
    }
  }

  private def now(): F[Nothing, LocalDateTime] = {
    F.sync(LocalDateTime.now()) // TODO: clock
  }

  private def dispatchRequest(channel: WebSocketChannel, sbody: String, decoded: Json): F[ServerTransportError, Json] = {
    assert(channel == this.ctx.channel)
    for {
      envelope <- F.fromEither(decoded.as[AsyncRequest]).leftMap(f => ServerTransportError.EnvelopeFormatError(sbody, f))
      _ <- F.sync {
        sessionMetaProvider.extract(this.ctx, meta.get(), envelope) match {
          case Some(value) =>
            meta.set(value)
          case None =>
        }
      }
      result <- call(WsServerInRequestContext(ctx, envelope, sbody), envelope.methodId, envelope.body)
      out = AsyncSuccess(Map.empty, result.value, envelope.id).asJson
    } yield {
      out
    }
  }

  def doSend(value: String): F[Throwable, Unit] = {
    F.async {
      f =>
        val websocketCallback = new WebSocketCallback[Unit] {
          override def complete(channel: WebSocketChannel, context: Unit): Unit = {
            f(Right(()))
          }

          override def onError(channel: WebSocketChannel, context: Unit, throwable: Throwable): Unit = {
            f(Left(throwable))
          }
        }
        WebSockets.sendText(value, this.ctx.channel, websocketCallback, ())
    }
  }
}
