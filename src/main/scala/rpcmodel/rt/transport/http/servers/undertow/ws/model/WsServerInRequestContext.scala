package rpcmodel.rt.transport.http.servers.undertow.ws.model

import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncRequest

case class WsServerInRequestContext(ctx: WsConnection, envelope: AsyncRequest, body: String)

