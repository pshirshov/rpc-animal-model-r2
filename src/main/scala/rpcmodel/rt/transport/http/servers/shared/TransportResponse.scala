package rpcmodel.rt.transport.http.servers.shared

import io.circe.derivation._
import io.circe.{Codec, Json}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.RemoteError

sealed trait TransportResponse

object TransportResponse {
  case class Success(data: Json) extends TransportResponse
  case class Failure(error: RemoteError) extends TransportResponse

  implicit def codec_Success: Codec.AsObject[Success] = deriveCodec
  implicit def codec_Failure: Codec.AsObject[Failure] = deriveCodec
  implicit def codec: Codec.AsObject[TransportResponse] = deriveCodec
}