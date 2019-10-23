package rpcmodel.rt.transport.http

import java.io.IOException
import java.nio.charset.StandardCharsets

import io.circe.parser._
import io.circe.{Json, Printer}
import io.undertow.server.{HttpHandler, HttpServerExchange}
import io.undertow.util.Headers
import izumi.functional.bio.{BIOAsync, BIOExit, BIORunner}
import rpcmodel.rt.transport.dispatch.GeneratedServerBase.ServerWireResponse
import rpcmodel.rt.transport.dispatch.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError


class HttpServerHandler[F[+ _, + _] : BIOAsync : BIORunner, C, DomainErrors]
(
  override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, C, Map[String, Seq[String]], Json]],
  printer: Printer,
  extractor: MethodIdExtractor,
  handler: TransportErrorHandler[DomainErrors, HttpServerExchange]
) extends AbstractServerHandler[F, C, Json] with HttpHandler {

  import izumi.functional.bio.BIO._


  override protected def bioAsync: BIOAsync[F] = implicitly

  override def handleRequest(exchange: HttpServerExchange): Unit = {
    if (exchange.isInIoThread) {
      exchange.dispatch(this)
      return
    }

    import scala.collection.JavaConverters._

    val result: F[ServerTransportError, ServerWireResponse[Json]] = for {
      headers <- F.sync {
        val nativeHeaders = exchange.getRequestHeaders
        nativeHeaders.getHeaderNames.asScala.map {
          n =>
            (n.toString, nativeHeaders.get(n).iterator().asScala.toSeq)
        }.toMap
      }
      id <- F.fromEither(extractor.extract(exchange.getRequestPath))
      body <- F.async[ServerTransportError, Array[Byte]](f => {
        exchange.getRequestReceiver.receiveFullBytes(
          (_: HttpServerExchange, message: Array[Byte]) => f(Right(message)),
          (_: HttpServerExchange, e: IOException) => f(Left(ServerTransportError.TransportException(e))))
      })
      sbody = new String(body, StandardCharsets.UTF_8)
      decoded <- F.fromEither(parse(sbody)).leftMap(f => ServerTransportError.JsonCodecError(sbody, f))
      result <- call(headers, id, decoded)
    } yield {
      result
    }

    val out = for {
      out <- result.sandbox.leftMap(_.toEither).redeemPure(handler.onError(exchange), v => TransportResponse.Success(v.value))
      json = out.value.printWith(printer)
      _ <- F.sync(exchange.getResponseHeaders.put(Headers.CONTENT_TYPE, "text/json"))
      _ <- F.sync {
        out match {
          case TransportResponse.Success(_) =>
            exchange.setStatusCode(200)
          case TransportResponse.Failure(_) =>
            exchange.setStatusCode(400)
          case TransportResponse.UnexpectedFailure(_) =>
            exchange.setStatusCode(500)
        }
      }
      _ <- F.sync(exchange.getResponseSender.send(json))
      _ <- F.sync(exchange.endExchange())
    } yield {
    }

    BIORunner[F].unsafeRunSyncAsEither(out) match {
      case BIOExit.Success(_) =>
      case f: BIOExit.Failure[Nothing] =>
        f.toEither match {
          case Right(_) => // nothing

          case Left(t) =>
            t.foreach(_.printStackTrace())
            ??? // TODO: call logger
        }
    }

  }
}


