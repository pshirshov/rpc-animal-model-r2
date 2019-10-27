package rpcmodel.rt.transport.http.servers.undertow.ws

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import io.circe._
import io.circe.parser.parse
import io.circe.syntax._
import io.undertow.websockets.core._
import izumi.functional.bio.{BIOAsync, BIORunner}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncResponse}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncResponse.AsyncSuccess
import rpcmodel.rt.transport.http.servers.shared._
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{WsConnection, WsServerInRequestContext}


class WebsocketSession[F[+ _, + _] : BIOAsync : BIORunner, Meta, C, DomainErrors]
(
  ctx: WsConnection,
  override protected val serverContextProvider: ContextProvider[F, ServerTransportError, WsServerInRequestContext, C],
  override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, C, Json]],
  printer: Printer,
  handler: TransportErrorHandler[DomainErrors, WsConnection],
  sessions: SessionManager[F, Meta],
  sessionMetaProvider: SessionMetaProvider[Meta],
) extends AbstractReceiveListener with AbstractServerHandler[F, C, WsServerInRequestContext, Json] {

  import izumi.functional.bio.BIO._

  override protected def bioAsync: BIOAsync[F] = implicitly

  val id: WsSessionId = WsSessionId(UUID.randomUUID())
  val meta = new AtomicReference(sessionMetaProvider.extractInitial(ctx))
  val pending = new ConcurrentHashMap[InvokationId, PendingResponse]() // TODO: cleanups

  def init(): Unit = {
    sessions.register(this)
  }

  init()


  def disconnect(): F[Throwable, Unit] = {
    F.syncThrowable(ctx.channel.sendClose())
      .catchAll(_ => F.syncThrowable(ctx.channel.close()))
  }

  override def onClose(webSocketChannel: WebSocketChannel, channel: StreamSourceFrameChannel): Unit = {
    sessions.drop(id)
  }

  override def onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage): Unit = {
    assert(channel == this.ctx.channel)

    val result = for {
      sbody <- F.pure(message.getData)
      decoded <- F.fromEither(parse(sbody)).leftMap(f => ServerTransportError.JsonCodecError(sbody, f))
      out <- if (decoded.asObject.exists(_.toMap.contains("methodId"))) { // incoming request
        for {
          out <- dispatchRequest(channel, sbody, decoded)
            .sandbox.leftMap(_.toEither)
            .redeemPure(handler.onError(this.ctx), v => TransportResponse.Success(v))
          json = out.value.printWith(printer)
          _ <- doSend(json)
            .catchAll(_ => F.pure(())) // TODO: handle exception?..
        } yield {
        }
      } else {
        for {
          envelope <- F.fromEither(decoded.as[AsyncResponse]).leftMap(f => ServerTransportError.EnvelopeFormatError(sbody, f))
          _ <- F.sync(pending.put(envelope.id, PendingResponse(envelope, LocalDateTime.now()))) // TODO: clock
        } yield {
        }
      }
    } yield {
      out
    }

    BIORunner[F].unsafeRunAsyncAsEither(result)(_ => ()) // TODO: handle exception?..
  }

  private def dispatchRequest(channel: WebSocketChannel, sbody: String, decoded: Json): F[ServerTransportError, Json] = {
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
