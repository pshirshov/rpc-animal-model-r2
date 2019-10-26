package rpcmodel.rt.transport.http.servers.undertow.ws

import java.util.UUID

import io.circe.{Json, Printer}
import izumi.functional.bio.BIO._
import izumi.functional.bio.{BIOAsync, BIORunner, BIOTransZio}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.http.clients.ahc.Repeat
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncSuccess}
import rpcmodel.rt.transport.http.servers.shared.InvokationId
import zio._

import scala.concurrent.duration._

class WsBuzzerTransport[F[+_, +_]: BIOAsync : BIOTransZio : BIORunner, Meta, RequestContext, ResponseContext]
(
  client: WsSessionBuzzer[F, Meta],
  printer: Printer,
  ctx: CtxDec[F, ClientDispatcherError, AsyncSuccess, ResponseContext],
) extends ClientTransport[F, RequestContext, ResponseContext, Json] {
  import io.circe.syntax._

  val trans = implicitly[BIOTransZio[F]]

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    //import zio.duration._
    for {
      id <- F.pure(InvokationId(UUID.randomUUID().toString))
      envelope = AsyncRequest(methodId, Map.empty, body, id)
      _ <- client.send(envelope.asJson.printWith(printer)).leftMap(e => ClientDispatcherError.UnknownException(e))
      p <-  trans.ofZio(Promise.make[ClientDispatcherError, ClientResponse[ResponseContext, Json]])
      check = trans.ofZio(for {
        status <- trans.toZio(client.takePending(id))
        _ <- status match {
          case Some(value) =>
            for {
              responseContext <- trans.toZio(ctx.decode(value.envelope))
              _ <- p.complete(IO.succeed(ClientResponse(responseContext, value.envelope.body)))
            } yield {

            }

          case None =>
            IO.unit
        }
        done <- p.isDone
        out <- if (done) {
          for {
            result <- p.poll
            f <- result match {
              case Some(value) =>
                value.map(f => Some(f))
              case None =>
                IO.succeed(None)
            }
          } yield {
            f
          }

        } else {
          IO.succeed(None)
        }
      } yield {
        out
      })
      result <- new Repeat[F].repeat(check, ClientDispatcherError.TimeoutException(id, methodId), 100.millis, 0, 20)
    } yield {
      System.err.println(s"WS: $result")
      result
    }
  }
}


