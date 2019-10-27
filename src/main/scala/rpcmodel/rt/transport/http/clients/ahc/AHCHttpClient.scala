package rpcmodel.rt.transport.http.clients.ahc

import java.net.URL
import java.util.function.BiFunction

import io.circe.{Json, Printer}
import izumi.functional.bio.BIO._
import izumi.functional.bio.BIOAsync
import org.asynchttpclient.{AsyncHttpClient, BoundRequestBuilder, Response}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError


class AHCHttpClient[F[+_, +_]: BIOAsync, RequestContext, ResponseContext]
(
  client: AsyncHttpClient,
  target: URL,
  printer: Printer,
  responseContextProvider: ContextProvider[F, ClientDispatcherError, Response, ResponseContext],
  hook: ClientRequestHook[RequestContext, BoundRequestBuilder],
) extends ClientTransport[F, RequestContext, ResponseContext, Json] {

  override def connect(): F[ClientDispatcherError, Unit] = F.unit

  override def disconnect(): F[ClientDispatcherError, Unit] = F.unit

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    import io.circe.parser._

    for {
      req <- F.pure(hook.onRequest(c, methodId, body, prepare(methodId, body)))
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
      responseContext <- responseContextProvider.decode(resp)
      body = resp.getResponseBody
      parsed <- F.fromEither(parse(body))
        .leftMap(e => ClientDispatcherError.ClientCodecFailure(List(IRTCodec.IRTCodecFailure.IRTParserException(e))))
    } yield {
      ClientResponse(responseContext, parsed)
    }
  }

  private def prepare(methodId: GeneratedServerBase.MethodId, body: Json): BoundRequestBuilder = {
    val url = new URL(target.getProtocol, target.getHost, target.getPort, s"${target.getFile}/${methodId.service.name}/${methodId.method.name}")
    client
      .preparePost(url.toString)
      .setBody(body.printWith(printer))
  }
}


