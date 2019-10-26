package rpcmodel.rt.transport.http.servers.undertow.ws.model

import java.time.LocalDateTime
import java.util.UUID

import io.undertow.websockets.core.WebSocketChannel
import rpcmodel.rt.transport.http.servers.shared.{EnvelopeIn, EnvelopeOut}

case class WSRequestContext(channel: WebSocketChannel, envelope: EnvelopeIn, body: String)

