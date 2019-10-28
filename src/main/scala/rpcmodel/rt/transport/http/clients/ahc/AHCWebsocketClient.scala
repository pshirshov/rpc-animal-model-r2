package rpcmodel.rt.transport.http.clients.ahc

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.circe.parser.parse
import io.circe.{Json, Printer}
import io.netty.util.concurrent.Future
import izumi.functional.bio.BIO._
import izumi.functional.bio.{BIOAsync, BIORunner, BIOTransZio}
import izumi.fundamentals.platform.entropy.{Entropy, Entropy2}
import org.asynchttpclient.AsyncHttpClient
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBase, GeneratedServerBaseImpl}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncResponse.{AsyncFailure, AsyncSuccess}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncResponse}
import rpcmodel.rt.transport.http.servers.shared.{AbstractServerHandler, InvokationId, PollingConfig}
import rpcmodel.rt.transport.http.servers.undertow.ws.RuntimeErrorHandler
import zio._

import scala.util.Try


class AHCWebsocketClient[F[+ _, + _] : BIOAsync : BIOTransZio : BIORunner, WsClientRequestContext, BuzzerRequestContext]
(
  client: AsyncHttpClient,
  target: URI,
  pollingConfig: PollingConfig,
  printer: Printer,
  hook: ClientRequestHook[WsClientRequestContext, AsyncRequest],
  buzzerContextProvider: ContextProvider[F, ServerTransportError, AsyncRequest, BuzzerRequestContext],
  buzzerDispatchers: Seq[GeneratedServerBaseImpl[F, BuzzerRequestContext, Json]] = Seq.empty,
  errHandler: RuntimeErrorHandler[ServerTransportError],
  random: Entropy2[F]
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
    val trans = implicitly[BIOTransZio[F]] // TODO: transzio
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

      p <- trans.ofZio(Promise.make[ClientDispatcherError, ClientResponse[Json]])

      check = trans.ofZio(for {
        status <- IO.effectTotal(pending.get(iid))
        _ <- status match {
          case Some(value) =>
            for {
              _ <- value match {
                case s: AsyncSuccess =>
                  p.complete(IO.succeed(ClientResponse(s.body)))
                case f: AsyncFailure =>
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
      data <- F.fromEither(value.as[AsyncRequest]).leftMap(f => ServerTransportError.EnvelopeFormatError(value.toString(), f))
      out <- call(data, data.methodId, data.body)
      conn <- F.sync(session.get())
      _ <- F.sync(conn.sendTextFrame(AsyncSuccess(Map.empty, out.value, data.id).asJson.printWith(printer)))
    } yield {

    }

    BIORunner[F].unsafeRunAsyncAsEither(work)(errHandler.handle(RuntimeErrorHandler.Context.WebsocketClientSession()))
  }

  private def handleResponse(value: Json): Unit = {
    for {
      data <- value.as[AsyncResponse]
      id <- Try(UUID.fromString(data.id.id)).toEither
    } yield {
      pending.put(InvokationId(id.toString), Some(data))
    }
    ()
  }

}



