package rpcmodel.rt.transport.http.servers.undertow

import io.circe.{Json, Printer}
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange
import izumi.functional.bio.{BIOAsync, BIORunner, Clock2}
import izumi.functional.mono.Entropy
import izumi.fundamentals.platform.functional.Identity
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.TransportErrorHandler
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{WsConnection, WsServerInRequestContext}
import rpcmodel.rt.transport.http.servers.undertow.ws.{RuntimeErrorHandler, SessionManager, SessionMetaProvider, WebsocketSession}

class WebsocketServerHandler[F[+ _, + _] : BIOAsync : BIORunner, Meta, C, +DomainErrors >: Nothing]
(
  dec: ContextProvider[F, ServerTransportError, WsServerInRequestContext, C],
  dispatchers: Seq[GeneratedServerBaseImpl[F, C, Json]],
  printer: Printer,
  onDomainError: TransportErrorHandler[DomainErrors, WsConnection],
  sessionMetaProvider: SessionMetaProvider[Meta],
  errHandler: RuntimeErrorHandler[ServerTransportError.Predefined],
  clock: Clock2[F],
  entropy: Entropy[Identity]
) extends WebSocketConnectionCallback {
  val sessionManager = new SessionManager[F, Meta]()

  override def onConnect(exchange: WebSocketHttpExchange, channel: WebSocketChannel): Unit = {
    val session = new WebsocketSession(
      WsConnection(channel, exchange),
      dec,
      dispatchers,
      printer,
      onDomainError,
      sessionManager,
      sessionMetaProvider,
      errHandler,
      clock,
      entropy,
    )

    channel.getReceiveSetter.set(session)
    channel.resumeReceives()
  }
}
