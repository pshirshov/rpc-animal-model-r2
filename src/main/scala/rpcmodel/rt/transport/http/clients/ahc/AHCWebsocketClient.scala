package rpcmodel.rt.transport.http.clients.ahc

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

import io.circe.parser.parse
import io.circe.{Json, Printer}
import io.netty.util.concurrent.Future
import izumi.functional.bio.BIO._
import izumi.functional.bio.{BIOAsync, BIOPrimitives, BIORunner}
import izumi.fundamentals.platform.entropy.Entropy2
import org.asynchttpclient.AsyncHttpClient
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBase, GeneratedServerBaseImpl}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncResponse.{AsyncFailure, AsyncSuccess}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncResponse}
import rpcmodel.rt.transport.http.servers.shared.{AbstractServerHandler, InvokationId, PollingConfig, TransportErrorHandler}
import rpcmodel.rt.transport.http.servers.undertow.ws.RuntimeErrorHandler


class AHCWebsocketClient[F[+ _, + _] : BIOAsync : BIOPrimitives : BIORunner, WsClientRequestContext, BuzzerRequestContext, +DomainErrors >: Nothing]
(
  client: AsyncHttpClient,
  target: URI,
  pollingConfig: PollingConfig,
  printer: Printer,
  hook: ClientRequestHook[WsClientRequestContext, AsyncRequest],
  buzzerContextProvider: ContextProvider[F, ServerTransportError, AsyncRequest, BuzzerRequestContext],
  buzzerDispatchers: Seq[GeneratedServerBaseImpl[F, BuzzerRequestContext, Json]] = Seq.empty,
  errHandler: RuntimeErrorHandler[ServerTransportError],
  random: Entropy2[F],
  handler: TransportErrorHandler[DomainErrors, AsyncRequest],
) extends ClientTransport[F, WsClientRequestContext, Json]
  with AbstractServerHandler[F, BuzzerRequestContext, AsyncRequest, Json]
  with AHCWSListener {

  import io.circe.syntax._

  override protected val serverContextProvider: ContextProvider[F, ServerTransportError, AsyncRequest, BuzzerRequestContext] = buzzerContextProvider
  override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, BuzzerRequestContext, Json]] = buzzerDispatchers

  private val pending = new ConcurrentHashMap[InvokationId, Option[AsyncResponse]]()
  private val session = new AHCWsClientSession(client, target, this)

  def connect(): F[ClientDispatcherError, Unit] = {
    for {
      _ <- F.sync(session.get())
    } yield {
    }
  }


  override def disconnect(): F[ClientDispatcherError, Unit] = {
    F.async {
      f =>
        session.get().sendCloseFrame().addListener((future: Future[Void]) => {
          if (future.isSuccess) {
            f(Right(()))
          } else {
            f(Left(ClientDispatcherError.UnknownException(future.cause())))
          }
        })
        ()
    }
  }

  override def dispatch(c: WsClientRequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[Json]] = {
    for {
      s <- F.sync(session.get())
      id <- random.nextTimeUUID()
      iid = InvokationId(id.toString)
      envelope = hook.onRequest(c, methodId, body, AsyncRequest(methodId, Map.empty, body, iid))
      _ <- F.sync(pending.put(iid, None))
      _ <- F.async[ClientDispatcherError, Unit] {
        f =>
          s.sendTextFrame(envelope.asJson.printWith(printer))
            .addListener((future: Future[Void]) => {
              if (future.isSuccess) {
                f(Right(()))
              } else {
                f(Left(ClientDispatcherError.UnknownException(future.cause())))
              }
            })
          ()
      }

      p <- BIOPrimitives[F].mkPromise[ClientDispatcherError, ClientResponse[Json]]

      check = for {
        status <- F.sync(pending.get(iid))
        _ <- status match {
          case Some(value) =>
            for {
              _ <- value match {
                case s: AsyncSuccess =>
                  p.succeed(ClientResponse(s.body))
                case f: AsyncFailure =>
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

      out <- check.repeatUntil(ClientDispatcherError.TimeoutException(iid, methodId), pollingConfig.sleep, pollingConfig.maxAttempts)
    } yield {
      out
    }
  }

  override protected def bioAsync: BIOAsync[F] = implicitly

  override def onTextMessage(payload: String): Unit = {
    parse(payload) match {
      case Left(_) =>
      // just ignore wrong packets
      case Right(value) =>
        if (value.asObject.exists(_.contains("methodId"))) {
          handleRequest(value)
        } else {
          handleResponse(value)
        }
    }
  }

  private def handleRequest(value: Json): Unit = {
    val work = for {
      conn <- F.sync(session.get())
      data <- F.fromEither(value.as[AsyncRequest]).leftMap(f => ServerTransportError.EnvelopeFormatError(value.toString(), f)) // all the improper requests will be ignored

      doCall = for {
        out <- call(data, data.methodId, data.body)
      } yield {
        AsyncSuccess(Map.empty, out.value, data.id)
      }

      resp <- doCall.sandbox.leftMap(_.toEither)
        .redeemPure[AsyncResponse](f => AsyncFailure(Map.empty, handler.toRemote(data)(f), Some(data.id)), s => s)
      _ <- F.sync(conn.sendTextFrame(resp.asJson.printWith(printer)))
    } yield {

    }

    BIORunner[F].unsafeRunAsyncAsEither(work)(errHandler.handle(RuntimeErrorHandler.Context.WebsocketClientSession()))
  }

  private def handleResponse(value: Json): Unit = {
    for {
      data <- value.as[AsyncResponse].left.map(f => ServerTransportError.EnvelopeFormatError(value.toString(), f))
      maybeId <- data.maybeId.toRight(ServerTransportError.UnknownRequest(value.toString()))
    } yield {
      pending.put(InvokationId(maybeId.id.substring(0, 127)), Some(data))
    }
    ()
  }

}



