package rpcmodel.rt.transport.http.servers.undertow

import io.circe.{Codec, Decoder, Encoder, Json}
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{MethodId, MethodName, ServiceName}

object WsEnvelope {

  import io.circe.derivation._

  case class InvokationId(id: String) extends AnyVal

  object InvokationId {
    implicit def InvokationId_codec: Codec[InvokationId] = Codec.from(Decoder.decodeString.map(s => InvokationId(s)), Encoder.encodeString.contramap(_.id))
  }

  case class WsResponseContext(channel: WebSocketChannel, exchange: WebSocketHttpExchange)

  case class EnvelopeIn(methodId: MethodId, headers: Map[String, Seq[String]], body: Json, id: InvokationId)

  object EnvelopeIn {
    implicit def MethodName_codec: Codec[MethodName] = Codec.from(Decoder.decodeString.map(s => MethodName(s)), Encoder.encodeString.contramap(_.name))

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