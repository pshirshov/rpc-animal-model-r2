package rpcmodel.rt.transport.http.servers.undertow.ws

import rpcmodel.rt.transport.http.servers.shared.EnvelopeIn
import rpcmodel.rt.transport.http.servers.undertow.ws.model.WsResponseContext

trait SessionMetaProvider[Meta] {
  def extractInitial(ctx: WsResponseContext): Meta
  def extract(ctx: WsResponseContext, previous: Meta, envelopeIn: EnvelopeIn): Option[Meta]

}
