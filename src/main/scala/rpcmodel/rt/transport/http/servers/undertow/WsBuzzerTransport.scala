package rpcmodel.rt.transport.http.servers.undertow

import java.util.UUID

import io.circe.{Json, Printer}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.http.clients.ahc.ClientRequestHook
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeIn, EnvelopeOut, InvokationId}
import zio._
import zio.clock.Clock


class WsBuzzerTransport[ /*F[+_, +_]: BIOAsync,*/ Meta, RequestContext, ResponseContext]
(
  client: WsBuzzer[IO, Meta],
  printer: Printer,
  ctx: CtxDec[IO, ClientDispatcherError, EnvelopeOut, ResponseContext],
  hook: ClientRequestHook[RequestContext] = ClientRequestHook.Passthrough,
) extends ClientTransport[ZIO[Clock, +?, +?], RequestContext, ResponseContext, Json] {
  import io.circe.syntax._


  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): ZIO[Clock, ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    import zio.duration._
    for {
      id <- IO.succeed(InvokationId(UUID.randomUUID().toString))
      envelope = EnvelopeIn(methodId, Map.empty, body, id)
      _ <- client.send(envelope.asJson.printWith(printer)).mapError(e => ClientDispatcherError.UnknownException(e))
      p <- Promise.make[ClientDispatcherError, ClientResponse[ResponseContext, Json]]
      check = for {
        status <- client.takePending(id)
        _ <- status match {
          case Some(value) =>
            for {
              responseContext <- ctx.decode(value.envelope)
              _ <- p.complete(IO.succeed(ClientResponse(responseContext, value.envelope.body)))
            } yield {

            }

          case None =>
            IO.unit
        }
        done <- p.isDone
      } yield {
        done
      }
      _ <- check.repeat((Schedule.spaced(100.millis) && ZSchedule.elapsed.whileOutput(_ < 2.seconds)) && Schedule.doUntil(a => a))
      result <- p.poll
      u <- result match {
        case Some(r) => r
        case None =>
          println("Buzzer response timeout")
          IO.fail(ClientDispatcherError.TimeoutException(id, methodId))
      }
    } yield {
      System.err.println(s"WS: $u")
      u
    }
  }
}


