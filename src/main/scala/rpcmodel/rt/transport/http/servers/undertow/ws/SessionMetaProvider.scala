package rpcmodel.rt.transport.http.servers.undertow.ws

import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncRequest
import rpcmodel.rt.transport.http.servers.undertow.ws.model.WsConnection

trait SessionMetaProvider[Meta] {
  def extractInitial(ctx: WsConnection): Meta
  def extract(ctx: WsConnection, previous: Meta, envelopeIn: AsyncRequest): Option[Meta]

}
