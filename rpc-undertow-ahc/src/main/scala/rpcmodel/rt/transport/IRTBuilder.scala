package rpcmodel.rt.transport

import java.net.URI

import io.circe.{Json, Printer}
import io.undertow.server.HttpServerExchange
import izumi.functional.bio.{BIOAsync, BIOPrimitives, BIORunner}
import izumi.functional.mono
import izumi.functional.mono.{Clock, Entropy}
import izumi.fundamentals.platform.functional.Identity
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.{AsyncHttpClient, BoundRequestBuilder}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.clients.ahc.{AHCClientContext, AHCHttpClient, AHCWebsocketClient, ClientRequestHook}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncRequest
import rpcmodel.rt.transport.http.servers.shared.{BasicTransportErrorHandler, MethodIdExtractor, PollingConfig, TransportErrorHandler}
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.http.servers.undertow.http.{HttpEnvelopeSupport, HttpEnvelopeSupportDefaultImpl, HttpEnvelopeSupportRestImpl}
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{WsConnection, WsServerInRequestContext}
import rpcmodel.rt.transport.http.servers.undertow.ws.{IdentifiedRequestContext, SessionMetaProvider, WsBuzzerTransport, WsSessionBuzzer}
import rpcmodel.rt.transport.http.servers.undertow.{HttpServerHandler, RuntimeErrorHandler, WebsocketServerHandler}

import scala.annotation.unchecked.uncheckedVariance

final case class IRTBuilder[+F[+ _, + _], -RequestContext]
(
  dispatchers: Seq[GeneratedServerBase[F, RequestContext, Json]] = Nil,
  defaultRuntimeErrHandler: Option[RuntimeErrorHandler[Any]] = None,
  defaultTransportErrHandler: Option[TransportErrorHandler[Any, Any]] = None,
  printer: Printer = Printer.noSpaces,
  entropy: Option[Entropy[Identity]] = None,
  clock: Option[Clock[Identity]] = None,
) {

  def makeHttpClient[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIORunner,
    Ctx <: RequestContext,
  ](
     target: String,
     hook: ClientRequestHook[AHCClientContext[Ctx], BoundRequestBuilder],
     client: AsyncHttpClient = null,
   ): AHCHttpClient[F2, Ctx] = {
    val client0 = Option(client).getOrElse(asyncHttpClient())

    new AHCHttpClient(
      client = client0,
      target = new URI(target),
      printer = printer,
      hook = hook,
    )
  }

  def makeHttpServer[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIORunner,
    Ctx <: RequestContext,
    Err,
  ](
     serverContextProvider: ContextProvider[F2, ServerTransportError, HttpRequestContext, Ctx],
     extractor: (MethodIdExtractor, RuntimeErrorHandler[Nothing], IRTBuilder[F2, Ctx]) => HttpEnvelopeSupport[F2] = null,
     methodIdExtractor: MethodIdExtractor = MethodIdExtractor.TailImpl,
     errHandler: RuntimeErrorHandler[Nothing] = null,
     handler: TransportErrorHandler[Err, HttpServerExchange] = null,
   ): HttpServerHandler[F2, Ctx, Err] = {
    makeHttpServerImpl(
      serverContextProvider = serverContextProvider,
      extractor = Option(extractor),
      methodIdExtractor = methodIdExtractor,
      errHandler = Option(errHandler),
      handler = Option(handler),
    )
  }

  def makeHttpRestServer[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIORunner,
    Ctx <: RequestContext,
    Err,
  ](
     serverContextProvider: ContextProvider[F2, ServerTransportError, HttpRequestContext, Ctx],
     methodIdExtractor: MethodIdExtractor = MethodIdExtractor.TailImpl,
     errHandler: RuntimeErrorHandler[Nothing] = null,
     handler: TransportErrorHandler[Err, HttpServerExchange] = null,
   ): HttpServerHandler[F2, Ctx, Err] = {
    makeHttpServerImpl[F2, Ctx, Err](
      serverContextProvider = serverContextProvider,
      extractor = Some((m, r, b) => new HttpEnvelopeSupportRestImpl(m, b.dispatchers, r)),
      methodIdExtractor = methodIdExtractor,
      errHandler = Option(errHandler),
      handler = Option(handler))
  }

  def makeWsClient[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIOPrimitives : BIORunner,
    WsClientRequestContext,
    Ctx <: RequestContext,
    Err,
  ](
     target: String,
     buzzerContextProvider: ContextProvider[F2, ServerTransportError, AsyncRequest, Ctx],
     hook: ClientRequestHook[IdentifiedRequestContext[WsClientRequestContext], AsyncRequest],
     client: AsyncHttpClient = null,
     pollingConfig: PollingConfig = PollingConfig.default,
     errHandler: RuntimeErrorHandler[ServerTransportError] = null,
     handler: TransportErrorHandler[Err, AsyncRequest] = null,
   ): AHCWebsocketClient[F2, WsClientRequestContext, Ctx, Err] = {

    val client0 = Option(client).getOrElse(asyncHttpClient())
    val entropy = Entropy.fromImpure(this.entropy.getOrElse(Entropy.Standard))
    val errHandler0 = Option(errHandler).orElse(defaultRuntimeErrHandler).getOrElse(RuntimeErrorHandler.print)
    val handler0 = Option(handler).orElse(defaultTransportErrHandler).getOrElse(BasicTransportErrorHandler.withoutDomain)

    new AHCWebsocketClient[F2, WsClientRequestContext, Ctx, Err](
      client = client0,
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
    Err,
  ](
     wsServerContextProvider: ContextProvider[F2, ServerTransportError, WsServerInRequestContext, Ctx],
     sessionMetaProvider: SessionMetaProvider[Meta],
     errHandler: RuntimeErrorHandler[ServerTransportError.Predefined] = null,
     handler: TransportErrorHandler[Err, WsConnection] = null,
   ): WebsocketServerHandler[F2, Meta, Ctx, Err] = {

    val clock = mono.Clock.fromImpure(this.clock.getOrElse(mono.Clock.Standard))
    val entropy = this.entropy.getOrElse(Entropy.Standard)
    val errHandler0 = Option(errHandler).orElse(defaultRuntimeErrHandler).getOrElse(RuntimeErrorHandler.print)
    val handler0 = Option(handler).orElse(defaultTransportErrHandler).getOrElse(BasicTransportErrorHandler.withoutDomain)

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
     hook: ClientRequestHook[IdentifiedRequestContext[Ctx], AsyncRequest],
     pollingConfig: PollingConfig = PollingConfig.default,
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

  private def makeHttpServerImpl[
    // @uncheckedVariance because of bug https://github.com/scala/bug/issues/11789
    F2[+x, +y] >: F[x, y]@uncheckedVariance : BIOAsync : BIORunner,
    Ctx <: RequestContext,
    Err,
  ](
     serverContextProvider: ContextProvider[F2, ServerTransportError, HttpRequestContext, Ctx],
     extractor: Option[(MethodIdExtractor, RuntimeErrorHandler[Nothing], IRTBuilder[F2, Ctx]) => HttpEnvelopeSupport[F2]],
     methodIdExtractor: MethodIdExtractor,
     errHandler: Option[RuntimeErrorHandler[Nothing]],
     handler: Option[TransportErrorHandler[Err, HttpServerExchange]],
   ): HttpServerHandler[F2, Ctx, Err] = {

    val errHandler0 = errHandler.orElse(defaultRuntimeErrHandler).getOrElse(RuntimeErrorHandler.print)
    val handler0 = handler.orElse(defaultTransportErrHandler).getOrElse(BasicTransportErrorHandler.withoutDomain)

    val extractor0 = extractor
      .getOrElse[(MethodIdExtractor, RuntimeErrorHandler[Nothing], IRTBuilder[F2, Ctx]) => HttpEnvelopeSupport[F2]] {
        (m, _, _) => new HttpEnvelopeSupportDefaultImpl[F2](m)
      }.apply(methodIdExtractor, errHandler0, this)

    new HttpServerHandler(
      dispatchers = dispatchers,
      serverContextProvider = serverContextProvider,
      printer = printer,
      extractor = extractor0,
      handler = handler0,
      errHandler = errHandler0,
    )
  }
}
