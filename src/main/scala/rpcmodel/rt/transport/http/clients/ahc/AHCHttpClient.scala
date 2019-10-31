package rpcmodel.rt.transport.http.clients.ahc

import java.net.URI
import java.util.function.BiFunction

import io.circe.{Decoder, Json, Printer}
import izumi.functional.bio.BIO._
import izumi.functional.bio.BIOAsync
import org.asynchttpclient.{AsyncHttpClient, BoundRequestBuilder, Response}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.RPCResult
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.http.servers.shared.Envelopes.RemoteError
import rpcmodel.rt.transport.http.servers.undertow.HttpServerHandler

trait BaseClientContext[RequestContext] {
  def methodId: GeneratedServerBase.MethodId
  def body: Json
  def rc: RequestContext
}

//case class SimpleRequestContext[RequestContext](
//                                 rc: RequestContext,
//                                 methodId: GeneratedServerBase.MethodId,
//                                 body: Json,
//                               ) extends BaseClientContext[RequestContext]

case class AHCClientContext[RequestContext](
                                             rc: RequestContext,
                                             printer: Printer,
                                             target: URI,
                                             client: AsyncHttpClient,
                                             methodId: GeneratedServerBase.MethodId,
                                             body: Json,
                                           ) extends BaseClientContext[RequestContext]

class AHCHttpClient[F[+ _, + _] : BIOAsync, RequestContext]
(
  client: AsyncHttpClient,
  target: URI,
  printer: Printer,
  hook: ClientRequestHook[AHCClientContext, RequestContext, BoundRequestBuilder],
) extends ClientTransport[F, RequestContext, Json] {

  override def connect(): F[ClientDispatcherError, Unit] = F.unit

  override def disconnect(): F[ClientDispatcherError, Unit] = F.unit

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[Json]] = {
    import io.circe.parser._

    for {
      req <- F.pure(hook.onRequest(AHCClientContext(c, printer, target, client, methodId, body), c => prepare(c.methodId, c.body)))
      resp <- F.async[ClientDispatcherError, Response] {
        f =>

          val handler = new BiFunction[Response, Throwable, Unit] {
            override def apply(t: Response, u: Throwable): Unit = {
              if (t != null) {
                f(Right(t))
              } else {
                f(Left(ClientDispatcherError.UnknownException(u)))
              }
            }
          }

          req.execute().toCompletableFuture.handle[Unit](handler)
          ()
      }
      body = resp.getResponseBody
      parsed <- F.fromEither(parse(body))
        .leftMap(e => ClientDispatcherError.ClientCodecFailure(List(IRTCodec.IRTCodecFailure.IRTParserException(e))))
      out <- {
        resp.getHeader(HttpServerHandler.responseTypeHeader.toString) match {
          case HttpServerHandler.rpcSuccess =>
            F.pure(ClientResponse(RPCResult.wireRight(parsed)))
          case HttpServerHandler.rpcFailure =>
            F.pure(ClientResponse(RPCResult.wireLeft(parsed)))
          case HttpServerHandler.successScalar =>
            F.pure(ClientResponse(parsed))
          case HttpServerHandler.transportFailure =>
            parseAs[RemoteError.Transport](parsed).map(ClientDispatcherError.ServerError).flatMap(f => F.fail(f))
          case HttpServerHandler.criticalFailure =>
            parseAs[RemoteError.Critical](parsed).map(ClientDispatcherError.ServerError).flatMap(f => F.fail(f))
        }
      }
    } yield {
      out
    }
  }

  def parseAs[T: Decoder](json: Json): F[ClientDispatcherError, T] = {
    F.fromEither(json.as[T])
      .leftMap(e => ClientDispatcherError.ClientCodecFailure(List(IRTCodec.IRTCodecFailure.IRTParserException(e))))
  }

  private def prepare(methodId: GeneratedServerBase.MethodId, body: Json): BoundRequestBuilder = {
    val url = new URI(
      target.getScheme,
      target.getUserInfo,
      target.getHost,
      target.getPort,
      s"${target.getPath}/${methodId.service.name}/${methodId.method.name}",
      target.getQuery,
      target.getFragment
    )
    client
      .preparePost(url.toString)
      .setBody(body.printWith(printer))
  }
}


