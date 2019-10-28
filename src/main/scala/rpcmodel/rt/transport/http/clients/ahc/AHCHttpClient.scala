package rpcmodel.rt.transport.http.clients.ahc

import java.net.URL
import java.util.function.BiFunction

import io.circe.{Json, Printer}
import izumi.functional.bio.BIO._
import izumi.functional.bio.BIOAsync
import org.asynchttpclient.{AsyncHttpClient, BoundRequestBuilder, Response}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.http.servers.shared.TransportResponse


class AHCHttpClient[F[+ _, + _] : BIOAsync, RequestContext]
(
  client: AsyncHttpClient,
  target: URL,
  printer: Printer,
  hook: ClientRequestHook[RequestContext, BoundRequestBuilder],
) extends ClientTransport[F, RequestContext, Json] {

  override def connect(): F[ClientDispatcherError, Unit] = F.unit

  override def disconnect(): F[ClientDispatcherError, Unit] = F.unit

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[Json]] = {
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
      body = resp.getResponseBody
      parsed <- F.fromEither(parse(body))
        .leftMap(e => ClientDispatcherError.ClientCodecFailure(List(IRTCodec.IRTCodecFailure.IRTParserException(e))))
      out <- F.fromEither(parsed.as[TransportResponse])
        .leftMap(e => ClientDispatcherError.ClientCodecFailure(List(IRTCodec.IRTCodecFailure.IRTParserException(e))))
      r <- out match {
        case TransportResponse.Success(data) =>
          F.pure(ClientResponse(data))
        case TransportResponse.Failure(error) =>
          F.fail(ClientDispatcherError.ServerError(error))
      }
    } yield {
      r

    }
  }

  private def prepare(methodId: GeneratedServerBase.MethodId, body: Json): BoundRequestBuilder = {
    val url = new URL(target.getProtocol, target.getHost, target.getPort, s"${target.getFile}/${methodId.service.name}/${methodId.method.name}")
    client
      .preparePost(url.toString)
      .setBody(body.printWith(printer))
  }
}


