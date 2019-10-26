package rpcmodel.rt.transport.http.servers.undertow

import java.util.UUID

import io.circe.{Json, Printer}
import izumi.functional.bio.{BIOAsync, BIORunner, BIOTransZio}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.http.clients.ahc.ClientRequestHook
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeIn, EnvelopeOut, InvokationId}
import zio._
import zio.clock.Clock
import izumi.functional.bio.BIO._

class WsBuzzerTransport[F[+_, +_]: BIOAsync : BIOTransZio : BIORunner, Meta, RequestContext, ResponseContext]
(
  client: WsBuzzer[F, Meta],
  printer: Printer,
  ctx: CtxDec[F, ClientDispatcherError, EnvelopeOut, ResponseContext],
  hook: ClientRequestHook[RequestContext] = ClientRequestHook.Passthrough,
) extends ClientTransport[F, RequestContext, ResponseContext, Json] {
  import io.circe.syntax._

  val trans = implicitly[BIOTransZio[F]]

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    import zio.duration._
    for {
      id <- F.pure(InvokationId(UUID.randomUUID().toString))
      envelope = EnvelopeIn(methodId, Map.empty, body, id)
      _ <- client.send(envelope.asJson.printWith(printer)).leftMap(e => ClientDispatcherError.UnknownException(e))
      p <-  trans.ofZio(Promise.make[ClientDispatcherError, ClientResponse[ResponseContext, Json]])
      check = for {
        status <- trans.toZio(client.takePending(id))
        _ <- status match {
          case Some(value) =>
            for {
              responseContext <- trans.toZio(ctx.decode(value.envelope))
              _ <-  p.complete(IO.succeed(ClientResponse(responseContext, value.envelope.body)))
            } yield {

            }

          case None =>
            IO.unit
        }
        done <- p.isDone
      } yield {
        done
      }
      _ <- trans.ofZio(check.repeat((Schedule.spaced(100.millis) && ZSchedule.elapsed.whileOutput(_ < 2.seconds)) && Schedule.doUntil(a => a)).provide(Clock.Live))
      result <- trans.ofZio(p.poll)
      u <- result match {
        case Some(r) => trans.ofZio(r)
        case None =>
          println("Buzzer response timeout")
          F.fail(ClientDispatcherError.TimeoutException(id, methodId))
      }
    } yield {
      System.err.println(s"WS: $u")
      u
    }
  }
}


