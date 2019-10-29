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
import io.circe.syntax._
import rpcmodel.rt.transport.http.servers.shared.Envelopes.RemoteError
import rpcmodel.rt.transport.http.servers.shared.Envelopes.RemoteError.ShortException
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
            res.kind match {
              case ResponseKind.Scalar =>
                scalarResponse(exchange, successScalar, 200, res.value)

              case ResponseKind.RpcSuccess =>
                rpcResponse(exchange, res, rpcSuccess)


              case ResponseKind.RpcFailure =>
                rpcResponse(exchange, res, rpcFailure)
            }

          case TransportResponse.Failure(e) =>
            e match {
              case r: RemoteError.Transport =>
                scalarResponse(exchange, transportFailure, 400, r.asJson)

              case r: RemoteError.Critical =>
                scalarResponse(exchange, criticalFailure, 500, r.asJson)
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

  private def scalarResponse(exchange: HttpServerExchange, scalar: String, code: Int, json: Json): Unit = {
    exchange.setStatusCode(code)
    exchange.getResponseHeaders.add(responseTypeHeader, scalar)
    exchange.getResponseSender.send(json.printWith(printer))
  }

  private def rpcResponse(exchange: HttpServerExchange, res: ServerWireResponse[Json], kind: String): Unit = {
    res.value.asObject.flatMap(_.values.headOption) match {
      case Some(value) =>
        scalarResponse(exchange, kind, 200, value)
      case None =>
        scalarResponse(exchange, criticalFailure, 500, RemoteError.Critical(List(ShortException("Unexpected RPC output", "Server bug: unexpected RPC layer output"))).asJson)
    }
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