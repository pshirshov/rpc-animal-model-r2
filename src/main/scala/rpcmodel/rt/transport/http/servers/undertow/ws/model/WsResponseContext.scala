package rpcmodel.rt.transport.http.servers.undertow.ws.model

import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange

case class WsResponseContext(channel: WebSocketChannel, exchange: WebSocketHttpExchange)
