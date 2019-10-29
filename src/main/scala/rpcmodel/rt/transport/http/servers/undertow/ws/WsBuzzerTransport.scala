package rpcmodel.rt.transport.http.servers.undertow.ws

import io.circe.{Json, Printer}
import izumi.functional.bio.BIO._
import izumi.functional.bio.{BIOAsync, BIOPrimitives, BIORunner, Entropy2}
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.http.clients.ahc.ClientRequestHook
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncResponse}
import rpcmodel.rt.transport.http.servers.shared.{InvokationId, PollingConfig}


class WsBuzzerTransport[F[+ _, + _] : BIOAsync : BIORunner : BIOPrimitives, Meta, BuzzerRequestContext]
(
  pollingConfig: PollingConfig,
  client: WsSessionBuzzer[F, Meta],
  hook: ClientRequestHook[BuzzerRequestContext, AsyncRequest],
  printer: Printer,
  random: Entropy2[F],
) extends ClientTransport[F, BuzzerRequestContext, Json] {

  import io.circe.syntax._

  override def connect(): F[ClientDispatcherError, Unit] = F.fail(ClientDispatcherError.OperationUnsupported())

  override def disconnect(): F[ClientDispatcherError, Unit] = client.disconnect().leftMap(t => ClientDispatcherError.UnknownException(t))

  override def dispatch(requestContext: BuzzerRequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[Json]] = {
    def work(id: InvokationId): F[ClientDispatcherError, ClientResponse[Json]] = for {
      envelope <- F.pure(hook.onRequest(requestContext, methodId, body, AsyncRequest(methodId, Map.empty, body, id)))
      _ <- client.setPending(id)
      _ <- client.send(envelope.asJson.printWith(printer)).leftMap(e => ClientDispatcherError.UnknownException(e))
      p <- BIOPrimitives[F].mkPromise[ClientDispatcherError, ClientResponse[Json]]

      check = for {
        status <- client.takePending(id)
        _ <- status match {
          case Some(value) =>
            for {
              _ <- value.envelope match {
                case s: AsyncResponse.AsyncSuccess =>
                  p.succeed(ClientResponse(s.body))
                case f: AsyncResponse.AsyncFailure =>
                  p.fail(ClientDispatcherError.ServerError(f.error))
              }
            } yield {

            }

          case None =>
            F.unit
        }
        done <- p.poll
        out <- (done match {
          case Some(value) =>
            value.map(f => Some(f))

          case None =>
            F.pure(None)
        }) : F[ClientDispatcherError, Option[ClientResponse[Json]]]
      } yield {
        out
      }
      result <- check.repeatUntil(ClientDispatcherError.TimeoutException(id, methodId), pollingConfig.sleep, pollingConfig.maxAttempts)
    } yield {
      result
    }


    for {
      id <- random.nextTimeUUID()
      iid = InvokationId(id.toString)
      result <- work(iid).guarantee(client.dropPending(iid))
    } yield {
      result
    }
  }
}


