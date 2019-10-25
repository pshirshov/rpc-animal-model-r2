package rpcmodel.rt.transport.http.servers.undertow

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import io.circe._
import io.circe.parser.parse
import io.circe.syntax._
import io.undertow.websockets.core._
import io.undertow.websockets.spi.WebSocketHttpExchange
import izumi.functional.bio.{BIOAsync, BIORunner}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeIn, EnvelopeOut, WsResponseContext}
import rpcmodel.rt.transport.http.servers.{AbstractServerHandler, TransportErrorHandler, TransportResponse}


case class WSRequestContext(channel: WebSocketChannel, envelope: EnvelopeIn, body: String)


case class WsSessionId(id: UUID) extends AnyVal

case class PendingResponse(envelope: EnvelopeOut, timestamp: LocalDateTime)

class WebsocketSession[F[+ _, + _] : BIOAsync : BIORunner, Meta, C, DomainErrors]
(
  override protected val dec: CtxDec[F, ServerTransportError, WSRequestContext, C],
  override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, C, Json]],
  channel: WebSocketChannel,
  exchange: WebSocketHttpExchange,
  printer: Printer,
  handler: TransportErrorHandler[DomainErrors, WsResponseContext],
  sessions: SessionManager[F, Meta],
  sessionMetaProvider: SessionMetaProvider[Meta],
) extends AbstractReceiveListener with AbstractServerHandler[F, C, WSRequestContext, Json] {

  import WsEnvelope._
  import izumi.functional.bio.BIO._

  val id: WsSessionId = WsSessionId(UUID.randomUUID())
  val meta = new AtomicReference(sessionMetaProvider.extractInitial(exchange, channel))
  val pending = new ConcurrentHashMap[InvokationId, PendingResponse]() // TODO: cleanups


  def init(): Unit = {
    sessions.register(this)
  }

  init()

  override protected def bioAsync: BIOAsync[F] = implicitly


  override def onClose(webSocketChannel: WebSocketChannel, channel: StreamSourceFrameChannel): Unit = {
    sessions.drop(id)
  }

  override def onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage): Unit = {
    assert(channel == this.channel)

    val result = for {
      sbody <- F.pure(message.getData)
      decoded <- F.fromEither(parse(sbody)).leftMap(f => ServerTransportError.JsonCodecError(sbody, f))

      out <- if (decoded.asObject.exists(_.toMap.contains("methodId"))) { // incoming request
        for {
          out <- dispatchRequest(channel, sbody, decoded)
            .sandbox.leftMap(_.toEither)
            .redeemPure(handler.onError(WsResponseContext(channel, exchange)), v => TransportResponse.Success(v))
          json = out.value.printWith(printer)
          _ <- doSend(json)
            .catchAll(t => F.pure(())) // TODO: handle exception?..
        } yield {
        }
      } else {
        for {
          envelope <- F.fromEither(decoded.as[EnvelopeOut]).leftMap(f => ServerTransportError.EnvelopeFormatError(sbody, f))
          _ <- F.sync(pending.put(envelope.id, PendingResponse(envelope, LocalDateTime.now()))) // TODO: clock
        } yield {
        }
      }
    } yield {
      out
    }

    BIORunner[F].unsafeRun(result)
  }

  private def dispatchRequest(channel: WebSocketChannel, sbody: String, decoded: Json): F[ServerTransportError, Json] = {
    for {
      envelope <- F.fromEither(decoded.as[EnvelopeIn]).leftMap(f => ServerTransportError.EnvelopeFormatError(sbody, f))
      _ <- F.sync {
        sessionMetaProvider.extract(exchange, channel, meta.get(), envelope) match {
          case Some(value) =>
            println(s"new meta of $id=$value")
            meta.set(value)
          case None =>
        }
      }
      result <- call(WSRequestContext(channel, envelope, sbody), envelope.methodId, envelope.body)
      out = EnvelopeOut(Map.empty, result.value, envelope.id).asJson
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
        println(s"Sending $value to client $id")
        WebSockets.sendText(value, channel, websocketCallback, ())
    }
  }
}
