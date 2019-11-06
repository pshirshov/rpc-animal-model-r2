package rpcmodel.rt.transport.http.servers.undertow.ws

import rpcmodel.rt.transport.dispatch.server.Envelopes.AsyncRequest
import rpcmodel.rt.transport.http.servers.undertow.ws.model.WsConnection

trait SessionMetaProvider[Meta] {
  def extractInitial(ctx: WsConnection): Meta
  def extract(ctx: WsConnection, previous: Meta, envelopeIn: AsyncRequest): Option[Meta]
}

object SessionMetaProvider {
  def simple[Meta](f: (WsConnection, Option[Meta], Option[AsyncRequest]) => Meta): SessionMetaProvider[Meta] = new SessionMetaProvider[Meta] {
    override def extractInitial(ctx: WsConnection): Meta = {
      f(ctx, None, None)
    }

    override def extract(ctx: WsConnection, previous: Meta, envelopeIn: AsyncRequest): Option[Meta] = {
      Some(f(ctx, Some(previous), Some(envelopeIn)))
    }
  }
}
