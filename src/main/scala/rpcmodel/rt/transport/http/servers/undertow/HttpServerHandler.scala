package rpcmodel.rt.transport.http.servers.undertow

import java.io.IOException
import java.nio.charset.StandardCharsets

import io.circe.parser._
import io.circe.{Json, Printer}
import io.undertow.server.{HttpHandler, HttpServerExchange}
import io.undertow.util.{Headers, HttpString}
import izumi.functional.bio.{BIOAsync, BIORunner}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{ResponseKind, ServerWireResponse}
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.{AbstractServerHandler, MethodIdExtractor, TransportErrorHandler, TransportResponse}
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.http.servers.undertow.ws.RuntimeErrorHandler
import io.circe.syntax._
import rpcmodel.rt.transport.http.servers.shared.Envelopes.RemoteError
// Server replies to incoming request:
//   - CtxDec may extract additional data from request and pass it as C
//   - Handlers may be proxied and may consider C
//   - server reply CANNOT be altered in case of positive response
//   - server reply can be altered for negative response with DomainError
// Client makes a request:
//   - Client request can be altered, user may pass custom context, C
//   - Server response may produce a custom context (see ClientResponse) but it will be ignored

class HttpServerHandler[F[+ _, + _] : BIOAsync : BIORunner, C, DomainErrors]
(
  override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, C, Json]],
  override protected val serverContextProvider: ContextProvider[F, ServerTransportError, HttpRequestContext, C],
  printer: Printer,
  extractor: MethodIdExtractor,
  handler: TransportErrorHandler[DomainErrors, HttpServerExchange],
  errHandler: RuntimeErrorHandler[Nothing],
) extends AbstractServerHandler[F, C, HttpRequestContext, Json] with HttpHandler {

  import izumi.functional.bio.BIO._
  import HttpServerHandler._

  override protected def bioAsync: BIOAsync[F] = implicitly

  override def handleRequest(exchange: HttpServerExchange): Unit = {
    val result: F[ServerTransportError, ServerWireResponse[Json]] = for {
      id <- F.fromEither(extractor.extract(exchange.getRequestPath))
      body <- F.async[ServerTransportError, Array[Byte]](f => {
        exchange.getRequestReceiver.receiveFullBytes(
          (_: HttpServerExchange, message: Array[Byte]) => f(Right(message)),
          (_: HttpServerExchange, e: IOException) => f(Left(ServerTransportError.TransportException(e))))
      })
      sbody = new String(body, StandardCharsets.UTF_8)
      decoded <- F.fromEither(parse(sbody)).leftMap(f => ServerTransportError.JsonCodecError(sbody, f))
      result <- call(HttpRequestContext(exchange, body, decoded), id, decoded)
    } yield {
      result
    }

    val out: F[Nothing, Unit] = for {
      out <- result.sandbox.leftMap(_.toEither)
        .redeemPure[TransportResponse](
          f => TransportResponse.Failure(handler.toRemote(exchange)(f)),
          v => TransportResponse.Success(v)
        )
      _ <- F.sync(exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, "text/json"))
      _ <- F.sync {
        out match {
          case TransportResponse.Success(res) =>
            exchange.setStatusCode(200)
            res.kind match {
              case ResponseKind.Scalar =>
                exchange.getResponseHeaders.add(responseTypeHeader, successScalar)
                exchange.getResponseSender.send(res.value.printWith(printer))

              case ResponseKind.RpcSuccess =>
                exchange.getResponseHeaders.add(responseTypeHeader, rpcSuccess)
                exchange.getResponseSender.send(res.value.asObject.get.values.head.printWith(printer))

              case ResponseKind.RpcFailure =>
                exchange.getResponseHeaders.add(responseTypeHeader, rpcFailure)
                exchange.getResponseSender.send(res.value.asObject.get.values.head.printWith(printer))
            }

          case TransportResponse.Failure(e) =>
            e match {
              case r: RemoteError.Transport =>
                exchange.setStatusCode(400)
                exchange.getResponseHeaders.add(responseTypeHeader, transportFailure)
                exchange.getResponseSender.send(r.asJson.printWith(printer))

              case r: RemoteError.Critical =>
                exchange.setStatusCode(500)
                exchange.getResponseHeaders.add(responseTypeHeader, criticalFailure)
                exchange.getResponseSender.send(r.asJson.printWith(printer))
            }
        }
      }
      _ <- F.sync(exchange.endExchange())
    } yield {
    }

    exchange.dispatch(new Runnable {
      override def run(): Unit = {
        BIORunner[F].unsafeRunAsyncAsEither(out)(errHandler.handle(RuntimeErrorHandler.Context.HttpRequest(exchange)))
      }
    })

  }
}


object HttpServerHandler {
  final val responseTypeHeader = HttpString.tryFromString("X-Response-Type")
  final val transportFailure = "Transport-Failure"
  final val criticalFailure = "Critical-Failure"
  final val rpcFailure = "Failure-Domain"
  final val rpcSuccess = "Success-Domain"
  final val successScalar = "Success-Scalar"
}