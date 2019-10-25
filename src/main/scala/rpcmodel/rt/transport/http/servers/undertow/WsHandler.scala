package rpcmodel.rt.transport.http.servers.undertow

import io.circe.{Json, Printer}
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange
import izumi.functional.bio.{BIOAsync, BIORunner}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.TransportErrorHandler
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.WsResponseContext

class WsHandler[F[+ _, + _] : BIOAsync : BIORunner, Meta, C, DomainErrors](
                                                                      dec: CtxDec[F, ServerTransportError, WSRequestContext, C],
                                                                      dispatchers: Seq[GeneratedServerBaseImpl[F, C, Json]],
                                                                      printer: Printer,
                                                                      onDomainError: TransportErrorHandler[DomainErrors, WsResponseContext],
                                                                      sessions: SessionManager[F, Meta],
                                                                      sessionMetaProvider: SessionMetaProvider[Meta]
                                                                    ) extends WebSocketConnectionCallback {
  override def onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel): Unit = {
    val session = new WebsocketSession(dec, dispatchers, channel, exchange, printer, onDomainError, sessions, sessionMetaProvider)
    channel.getReceiveSetter.set(session)
    channel.resumeReceives()
  }
}
