package rpcmodel.rt.transport.http.servers.undertow.ws

import rpcmodel.rt.transport.dispatch.server.WsSessionId

trait SessionManager[F[+ _, + _], Meta] {
  def register(value: WebsocketSession[F, Meta, _, _]): Unit
  def drop(id: WsSessionId): Unit
  def filterSessions(pred: Meta => Boolean): Seq[WsSessionBuzzer[F, Meta]]
}
