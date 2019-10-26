package rpcmodel.rt.transport.http.servers.shared

import io.circe.derivation._
import io.circe.{Codec, Json}

sealed trait TransportResponse {
  def value: Json
}

object TransportResponse {
  implicit def codec_Success: Codec.AsObject[Success] = deriveCodec
  implicit def codec_Failure: Codec.AsObject[Failure] = deriveCodec
  implicit def codec_UnexpectedFailure: Codec.AsObject[UnexpectedFailure] = deriveCodec
  implicit def codec_AbstractFailure: Codec.AsObject[AbstractFailure] = deriveCodec
  implicit def codec: Codec.AsObject[TransportResponse] = deriveCodec

  sealed trait AbstractFailure extends TransportResponse
  case class Success(value: Json) extends TransportResponse
  case class Failure(value: Json) extends AbstractFailure
  case class UnexpectedFailure(value: Json) extends AbstractFailure
}