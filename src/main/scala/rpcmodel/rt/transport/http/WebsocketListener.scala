package rpcmodel.rt.transport.http

import io.circe._
import io.circe.parser.parse
import io.circe.syntax._
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core._
import io.undertow.websockets.spi.WebSocketHttpExchange
import izumi.functional.bio.{BIOAsync, BIOExit, BIORunner}
import rpcmodel.rt.transport.dispatch.GeneratedServerBase.{MethodId, MethodName, ServiceName}
import rpcmodel.rt.transport.dispatch.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.WsEnvelope.WsResponseContext



object WsEnvelope {
  import io.circe.derivation._
  case class InvokationId(id: String) extends AnyVal
  object InvokationId {
    implicit def InvokationId_codec: Codec[InvokationId] =  Codec.from(Decoder.decodeString.map(s => InvokationId(s)), Encoder.encodeString.contramap(_.id))
  }

  case class WsResponseContext(channel: WebSocketChannel, exchange: WebSocketHttpExchange)

  case class EnvelopeIn(methodId: MethodId, headers: Map[String, Seq[String]], body: Json, id: InvokationId)

  object EnvelopeIn {
    implicit def MethodName_codec: Codec[MethodName] =  Codec.from(Decoder.decodeString.map(s => MethodName(s)), Encoder.encodeString.contramap(_.name))
    implicit def ServiceName_codec: Codec[ServiceName] = Codec.from(Decoder.decodeString.map(s => ServiceName(s)), Encoder.encodeString.contramap(_.name))
    implicit def MethodId_codec: Codec[MethodId] = deriveCodec
    implicit def EnvelopeIn_codec: Codec[EnvelopeIn] = deriveCodec
  }

  case class EnvelopeOut(headers: Map[String, Seq[String]], body: Json, id: InvokationId)
  object EnvelopeOut {
    implicit def EnvelopeOut_codec: Codec[EnvelopeOut] = deriveCodec
  }

  case class EnvelopeOutErr(headers: Map[String, Seq[String]], error: Json, id: InvokationId)
  object EnvelopeOutErr {
    implicit def EnvelopeOutErr_codec: Codec[EnvelopeOutErr] = deriveCodec
  }

}



class WsHandler[F[+ _, + _] : BIOAsync : BIORunner, C, DomainErrors](
                                                                      dispatchers: Seq[GeneratedServerBaseImpl[F, C, Map[String, Seq[String]], Json]],
                                                                      printer: Printer,
                                                                      onDomainError: TransportErrorHandler[DomainErrors, WsResponseContext],
                                                                    ) extends WebSocketConnectionCallback {
  override def onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel): Unit = {
    channel.getReceiveSetter.set(new WebsocketListener(channel, exchange, dispatchers, printer, onDomainError))
    channel.resumeReceives()
  }
}
class WebsocketListener[F[+ _, + _] : BIOAsync : BIORunner, C, DomainErrors](
                                                                              channel: WebSocketChannel,
                                                                              exchange: WebSocketHttpExchange,
                                                                              override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, C, Map[String, Seq[String]], Json]],
                                                                              printer: Printer,
                                                                              handler: TransportErrorHandler[DomainErrors, WsResponseContext],
                                                                            ) extends  AbstractReceiveListener with AbstractServerHandler[F, C, Json] {
  import WsEnvelope._
  import izumi.functional.bio.BIO._

  override protected def bioAsync: BIOAsync[F] = implicitly

  private val websocketCallback = new WebSocketCallback[WsResponseContext] {
    override def complete(channel: WebSocketChannel, context: WsResponseContext): Unit = {}

    override def onError(channel: WebSocketChannel, context: WsResponseContext, throwable: Throwable): Unit = {}
  }

  override def onFullTextMessage(channel: WebSocketChannel, message: BufferedTextMessage): Unit = {

    val result = for {
      sbody <- F.pure(message.getData)
      decoded <- F.fromEither(parse(sbody)).leftMap(f => ServerTransportError.JsonCodecError(sbody, f))
      envelope <- F.fromEither(decoded.as[EnvelopeIn]).leftMap(f => ServerTransportError.EnvelopeFormatError(sbody, f))
      result <- call(envelope.headers, envelope.methodId, envelope.body)
      out = EnvelopeOut(Map.empty, result.value, envelope.id).asJson
    } yield {
      out
    }

    val ctx = WsResponseContext(channel, exchange)

    val out = for {
      out <- result.sandbox.leftMap(_.toEither).redeemPure(handler.onError(ctx), v => TransportResponse.Success(v))
      json = out.value.printWith(printer)
      _ <- F.sync(doSend(json, ctx))
    } yield {
    }

    BIORunner[F].unsafeRunSyncAsEither(out) match {
      case BIOExit.Success(_) =>
      case f: BIOExit.Failure[Nothing] =>
        f.toEither match {
          case Right(_) => // nothing

          case Left(t) =>
            t.foreach(_.printStackTrace())
            ??? // TODO: call logger
        }
    }
  }

  private def doSend(value: String, ctx: WsResponseContext): Unit = {
    WebSockets.sendText(value, ctx.channel, websocketCallback, ctx)
  }
}
