package rpcmodel.rt.transport.http.servers.shared

import java.time.LocalDateTime
import java.util.UUID

import io.circe.derivation._
import io.circe._
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{MethodId, MethodName, ServiceName}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncResponse

import scala.concurrent.duration.FiniteDuration

case class InvokationId(id: String) extends AnyVal

case class PollingConfig(sleep: FiniteDuration, maxAttempts: Int)

object InvokationId {
  implicit def InvokationId_codec: Codec[InvokationId] = Codec.from(Decoder.decodeString.map(s => InvokationId(s)), Encoder.encodeString.contramap(_.id))
}

case class WsSessionId(id: UUID) extends AnyVal

case class PendingResponse(envelope: AsyncResponse, timestamp: LocalDateTime)


object Envelopes {

  case class AsyncRequest(methodId: MethodId, headers: Map[String, Seq[String]], body: Json, id: InvokationId)

  object AsyncRequest {
    implicit def MethodName_codec: Codec[MethodName] = Codec.from(Decoder.decodeString.map(s => MethodName(s)), Encoder.encodeString.contramap(_.name))

    implicit def ServiceName_codec: Codec[ServiceName] = Codec.from(Decoder.decodeString.map(s => ServiceName(s)), Encoder.encodeString.contramap(_.name))

    implicit def MethodId_codec: Codec[MethodId] = deriveCodec

    implicit def EnvelopeIn_codec: Codec[AsyncRequest] = deriveCodec
  }

  sealed trait AsyncResponse {
    def headers: Map[String, Seq[String]]
    def maybeId: Option[InvokationId]
  }

  object AsyncResponse {

    import io.circe.syntax._

    implicit def EnvelopePoly_codec: Codec[AsyncResponse] = Codec.from(Decoder.decodeJson.flatMap {
      json =>
        val out = if (json.asObject.exists(_.contains("body"))) {
          implicitly[Decoder[AsyncSuccess]]
        } else {
          implicitly[Decoder[AsyncFailure]]
        }
        out.asInstanceOf[Decoder[AsyncResponse]]
    }, Encoder.encodeJson.contramap({ case s: AsyncSuccess =>
      s.asJson
    case f: AsyncFailure =>
      f.asJson
    }))


    case class AsyncSuccess(headers: Map[String, Seq[String]], body: Json, id: InvokationId) extends AsyncResponse {
      override def maybeId: Option[InvokationId] = Some(id)
    }

    object AsyncSuccess {
      implicit def EnvelopeOut_codec: Codec[AsyncSuccess] = deriveCodec
    }

    case class AsyncFailure(headers: Map[String, Seq[String]], error: RemoteError, id: Option[InvokationId]) extends AsyncResponse {
      override def maybeId: Option[InvokationId] = id
    }

    object AsyncFailure {
      implicit def EnvelopeOutErr_codec: Codec[AsyncFailure] = deriveCodec
    }

  }

  sealed trait RemoteError

  object RemoteError {
    implicit def RemoteError_codec: Codec[RemoteError] = deriveCodec

    case class Transport(properties: Map[String, Json]) extends RemoteError
    object Transport {
      implicit def Transport_codec: Codec.AsObject[Transport] = deriveCodec
    }

    case class ShortException(kind: String, message: String)
    object ShortException {
      def of(t: Throwable): ShortException = ShortException(t.getClass.getName, t.getMessage)
      implicit def ShortException_codec: Codec.AsObject[ShortException] = deriveCodec
    }

    case class Critical(messages: Seq[ShortException]) extends RemoteError
    object Critical {
      implicit def Critical_codec: Codec.AsObject[Critical] = deriveCodec
    }
  }
}

