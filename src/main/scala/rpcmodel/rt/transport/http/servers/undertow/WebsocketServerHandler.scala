package rpcmodel.rt.transport.http.servers.undertow

import io.circe.{Json, Printer}
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange
import izumi.functional.bio.{BIOAsync, BIORunner}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.TransportErrorHandler
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{WSRequestContext, WsResponseContext}
import rpcmodel.rt.transport.http.servers.undertow.ws.{SessionManager, SessionMetaProvider, WebsocketSession}

class WebsocketServerHandler[F[+ _, + _] : BIOAsync : BIORunner, Meta, C, DomainErrors](
                                                                      dec: CtxDec[F, ServerTransportError, WSRequestContext, C],
                                                                      dispatchers: Seq[GeneratedServerBaseImpl[F, C, Json]],
                                                                      printer: Printer,
                                                                      onDomainError: TransportErrorHandler[DomainErrors, WsResponseContext],
                                                                      sessionMetaProvider: SessionMetaProvider[Meta]
                                                                    ) extends WebSocketConnectionCallback {
  val sessionManager = new SessionManager[F, Meta]()

  override def onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel): Unit = {

    val session = new WebsocketSession(
      WsResponseContext(channel, exchange),
      dec,
      dispatchers,
      printer,
      onDomainError,
      sessionManager,
      sessionMetaProvider
    )

    channel.getReceiveSetter.set(session)
    channel.resumeReceives()
  }
}
