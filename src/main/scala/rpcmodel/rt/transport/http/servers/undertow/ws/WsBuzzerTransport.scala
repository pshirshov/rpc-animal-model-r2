package rpcmodel.rt.transport.http.servers.undertow.ws

import java.util.UUID

import io.circe.{Json, Printer}
import izumi.functional.bio.BIO._
import izumi.functional.bio.{BIOAsync, BIORunner, BIOTransZio}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncResponse}
import rpcmodel.rt.transport.http.servers.shared.{InvokationId, PollingConfig}
import rpcmodel.rt.transport.http.servers.undertow.ws.model.BuzzerRequestContext
import zio._





class WsBuzzerTransport[F[+ _, + _] : BIOAsync : BIOTransZio : BIORunner, Meta, ResponseContext]
(
  pollingConfig: PollingConfig,
  client: WsSessionBuzzer[F, Meta],
  printer: Printer,
  ctx: ContextProvider[F, ClientDispatcherError, AsyncResponse, ResponseContext],
) extends ClientTransport[F, BuzzerRequestContext, ResponseContext, Json] {

  import io.circe.syntax._

  private val trans = implicitly[BIOTransZio[F]]

  override def connect(): F[ClientDispatcherError, Unit] = F.fail(ClientDispatcherError.OperationUnsupported())

  override def disconnect(): F[ClientDispatcherError, Unit] = client.disconnect().leftMap(t => ClientDispatcherError.UnknownException(t))

  override def dispatch(requestContext: BuzzerRequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    for {
      id <- F.pure(InvokationId(UUID.randomUUID().toString))
      envelope = AsyncRequest(methodId, requestContext.headers, body, id)
      _ <- client.send(envelope.asJson.printWith(printer)).leftMap(e => ClientDispatcherError.UnknownException(e))
      p <- trans.ofZio(Promise.make[ClientDispatcherError, ClientResponse[ResponseContext, Json]])
      check = trans.ofZio(for {
        status <- trans.toZio(client.takePending(id))
        _ <- status match {
          case Some(value) =>
            for {
              responseContext <- trans.toZio(ctx.decode(value.envelope))
              _ <- value.envelope match {
                case s: AsyncResponse.AsyncSuccess =>
                  p.complete(IO.succeed(ClientResponse(responseContext, s.body)))
                case f: AsyncResponse.AsyncFailure =>
                  p.complete(IO.fail(ClientDispatcherError.ServerError(f.error)))
              }
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
      result <- check.repeatUntil(ClientDispatcherError.TimeoutException(id, methodId), pollingConfig.sleep, pollingConfig.maxAttempts)
    } yield {
      result
    }
  }
}


