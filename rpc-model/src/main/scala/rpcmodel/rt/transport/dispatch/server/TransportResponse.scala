package rpcmodel.rt.transport.dispatch.server

import io.circe.Json
import rpcmodel.rt.transport.dispatch.server.Envelopes.RemoteError
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ServerWireResponse

sealed trait TransportResponse

object TransportResponse {
  case class Success(data: ServerWireResponse[Json]) extends TransportResponse
  case class Failure(error: RemoteError) extends TransportResponse
}