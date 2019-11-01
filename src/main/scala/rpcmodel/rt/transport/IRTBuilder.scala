package rpcmodel.rt.transport

import java.net.URI

import io.circe.{Json, Printer}
import io.undertow.server.HttpServerExchange
import izumi.functional.bio.{BIOAsync, BIOPrimitives, BIORunner, Clock2}
import izumi.functional.mono
import izumi.functional.mono.Entropy
import izumi.fundamentals.platform.functional.Identity
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.{AsyncHttpClient, BoundRequestBuilder}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.clients.ahc.{AHCClientContext, AHCHttpClient, AHCWebsocketClient, ClientRequestHook}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncRequest
import rpcmodel.rt.transport.http.servers.shared.{BasicTransportErrorHandler, PollingConfig, TransportErrorHandler}
import rpcmodel.rt.transport.http.servers.undertow.http.HttpEnvelopeSupport
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{WsConnection, WsServerInRequestContext}
import rpcmodel.rt.transport.http.servers.undertow.ws.{IdentifiedRequestContext, SessionMetaProvider, WsBuzzerTransport, WsSessionBuzzer}
import rpcmodel.rt.transport.http.servers.undertow.{HttpServerHandler, RuntimeErrorHandler, WebsocketServerHandler}

import scala.annotation.unchecked.uncheckedVariance

final case class IRTBuilder[F[+ _, + _], -RequestContext, -DomainErrors >: Nothing]
(
  dispatchers: Seq[GeneratedServerBase[F, RequestContext, Json]] = Nil,
  pollingConfig: PollingConfig = PollingConfig.default,
  defaultRuntimeErrHandler: Option[RuntimeErrorHandler[Any]] = None,
  defaultTransportErrHandler: Option[TransportErrorHandler[Any, Any]] = None,
  printer: Printer = Printer.noSpaces,
  entropy: Option[Entropy[Identity]] = None,
  clock: Option[Clock2[F]] = None,
  client: Option[AsyncHttpClient] = None,
  extractor: Option[HttpEnvelopeSupport[F]] = None,
) {

  def makeHttpClient[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIORunner,
    Ctx <: RequestContext,
  ](
     target: String,
     hook: ClientRequestHook[AHCClientContext, Ctx, BoundRequestBuilder],
   ): AHCHttpClient[F2, Ctx] = {
    val client = this.client.getOrElse(asyncHttpClient())

    new AHCHttpClient(
      client = client,
      target = new URI(target),
      printer = printer,
      hook = hook,
    )
  }

  def makeHttpServer[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIORunner,
    Ctx <: RequestContext,
    Err <: DomainErrors,
  ](
     serverContextProvider: ContextProvider[F, ServerTransportError, HttpRequestContext, Ctx],
     errHandler: Option[RuntimeErrorHandler[Nothing]] = None,
     handler: Option[TransportErrorHandler[Err, HttpServerExchange]] = None,
   ): HttpServerHandler[F2, Ctx, Err] = {

    val errHandler0 = errHandler.orElse(defaultRuntimeErrHandler).getOrElse(RuntimeErrorHandler.print)
    val handler0 = handler.orElse(defaultTransportErrHandler).getOrElse(BasicTransportErrorHandler.withoutDomain)

    new HttpServerHandler(
      dispatchers = dispatchers,
      serverContextProvider = serverContextProvider,
      printer = printer,
      extractor = extractor getOrElse HttpEnvelopeSupport.default,
      handler = handler0,
      errHandler = errHandler0,
    )
  }

  def makeWsClient[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIOPrimitives : BIORunner,
    WsClientRequestContext,
    Ctx <: RequestContext,
    Err <: DomainErrors,
  ](
     target: String,
     buzzerContextProvider: ContextProvider[F2, ServerTransportError, AsyncRequest, Ctx],
     hook: ClientRequestHook[IdentifiedRequestContext, WsClientRequestContext, AsyncRequest],
     errHandler: Option[RuntimeErrorHandler[ServerTransportError]] = None,
     handler: Option[TransportErrorHandler[Err, AsyncRequest]] = None,
   ): AHCWebsocketClient[F2, WsClientRequestContext, Ctx, Err] = {

    val client = this.client.getOrElse(asyncHttpClient())
    val entropy = Entropy.fromImpure(this.entropy.getOrElse(Entropy.Standard))
    val errHandler0 = errHandler.orElse(defaultRuntimeErrHandler).getOrElse(RuntimeErrorHandler.print)
    val handler0 = handler.orElse(defaultTransportErrHandler).getOrElse(BasicTransportErrorHandler.withoutDomain)

    new AHCWebsocketClient[F2, WsClientRequestContext, Ctx, Err](
      client = client,
      target = new URI(target),
      pollingConfig = pollingConfig,
      buzzerDispatchers = dispatchers,
      buzzerContextProvider = buzzerContextProvider,
      hook = hook,
      handler = handler0,
      errHandler = errHandler0,
      printer = printer,
      random = entropy,
    )
  }

  def makeWsServer[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIORunner,
    Meta,
    Ctx <: RequestContext,
    Err <: DomainErrors,
  ](
     wsServerContextProvider: ContextProvider[F2, ServerTransportError, WsServerInRequestContext, Ctx],
     sessionMetaProvider: SessionMetaProvider[Meta],
     errHandler: Option[RuntimeErrorHandler[ServerTransportError.Predefined]] = None,
     handler: Option[TransportErrorHandler[Err, WsConnection]] = None,
   ): WebsocketServerHandler[F2, Meta, Ctx, Err] = {

    val clock = this.clock.getOrElse(mono.Clock.fromImpure(new mono.Clock.Standard))
    val entropy = this.entropy.getOrElse(Entropy.Standard)
    val errHandler0 = errHandler.orElse(defaultRuntimeErrHandler).getOrElse(RuntimeErrorHandler.print)
    val handler0 = handler.orElse(defaultTransportErrHandler).getOrElse(BasicTransportErrorHandler.withoutDomain)

    new WebsocketServerHandler(
      dispatchers = dispatchers,
      dec = wsServerContextProvider,
      sessionMetaProvider = sessionMetaProvider,
      onDomainError = handler0,
      errHandler = errHandler0,
      printer = printer,
      clock = clock,
      entropy = entropy,
    )
  }

  def makeWsBuzzerTransport[
    F2[+ _, + _] : BIOAsync : BIOPrimitives,
    Meta,
    Ctx <: RequestContext
  ](
     client: WsSessionBuzzer[F2, Meta],
     hook: ClientRequestHook[IdentifiedRequestContext, Ctx, AsyncRequest],
   ): WsBuzzerTransport[F2, Meta, Ctx] = {
    val entropy = Entropy.fromImpure(this.entropy.getOrElse(Entropy.Standard))

    new WsBuzzerTransport(
      pollingConfig = pollingConfig,
      client = client,
      hook = hook,
      printer = printer,
      random = entropy,
    )
  }
}
