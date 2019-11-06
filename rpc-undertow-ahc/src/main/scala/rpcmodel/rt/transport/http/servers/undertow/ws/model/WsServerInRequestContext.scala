package rpcmodel.rt.transport.http.servers.undertow.ws.model

import rpcmodel.rt.transport.dispatch.server.Envelopes.AsyncRequest

case class WsServerInRequestContext(ctx: WsConnection, envelope: AsyncRequest, body: String)

