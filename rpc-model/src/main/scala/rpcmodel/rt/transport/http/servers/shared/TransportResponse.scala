package rpcmodel.rt.transport.http.servers.shared

import io.circe.Json
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ServerWireResponse
import rpcmodel.rt.transport.http.servers.shared.Envelopes.RemoteError

sealed trait TransportResponse

object TransportResponse {
  case class Success(data: ServerWireResponse[Json]) extends TransportResponse
  case class Failure(error: RemoteError) extends TransportResponse
}