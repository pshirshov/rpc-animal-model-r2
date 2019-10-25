package rpcmodel.rt.transport.http.servers.undertow

import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.EnvelopeIn

trait SessionMetaProvider[Meta] {
  def extractInitial(exchange: WebSocketHttpExchange, channel: WebSocketChannel): Meta
  def extract(exchange: WebSocketHttpExchange, channel: WebSocketChannel, previous: Meta, envelopeIn: EnvelopeIn): Option[Meta]

}
