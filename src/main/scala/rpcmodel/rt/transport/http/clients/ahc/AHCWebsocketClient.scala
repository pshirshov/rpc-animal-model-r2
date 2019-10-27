package rpcmodel.rt.transport.http.clients.ahc

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.circe.parser.parse
import io.circe.{Json, Printer}
import io.netty.util.concurrent.{Future, GenericFutureListener}
import izumi.functional.bio.BIO._
import izumi.functional.bio.{BIOAsync, BIORunner, BIOTransZio}
import org.asynchttpclient.AsyncHttpClient
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBase, GeneratedServerBaseImpl}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncSuccess}
import rpcmodel.rt.transport.http.servers.shared.{AbstractServerHandler, InvokationId, PollingConfig}
import zio._

import scala.concurrent.duration._
import scala.util.Try

class Repeat[F[+ _, + _] : BIOAsync] {
  def repeat[E, V](action: F[E, Option[V]], onTimeout: => E, sleep: Duration, attempts: Int, maxAttempts: Int): F[E, V] = {
    action.flatMap {
      case Some(value) =>
        F.pure(value)
      case None =>
        if (attempts <= maxAttempts) {
          F.sleep(sleep) *> repeat(action, onTimeout, sleep, attempts + 1, maxAttempts)
        } else {
          F.fail(onTimeout)
        }
    }
  }
}



class AHCWebsocketClient[F[+ _, + _] : BIOAsync : BIOTransZio : BIORunner, RequestContext, ResponseContext, ServerRequestContext]
(
  client: AsyncHttpClient,
  target: URI,
  pollingConfig: PollingConfig,
  printer: Printer,
  ctx: CtxDec[IO, ClientDispatcherError, AsyncSuccess, ResponseContext],
  override protected val dispatchers: Seq[GeneratedServerBaseImpl[F, ServerRequestContext, Json]],
  override protected val dec: CtxDec[F, ServerTransportError, AsyncRequest, ServerRequestContext],
) extends ClientTransport[F, RequestContext, ResponseContext, Json]
  with AbstractServerHandler[F, ServerRequestContext, AsyncRequest, Json]
  with AHCWSListener {

  import io.circe.syntax._

  private val pending = new ConcurrentHashMap[InvokationId, Option[AsyncSuccess]]()
  private val session = new AHCWsClientSession(client, target, this)

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    val trans = implicitly[BIOTransZio[F]]
    for {
      s <- F.sync(session.get())
      id = InvokationId(UUID.randomUUID().toString)
      envelope = AsyncRequest(methodId, Map.empty, body, id)
      _ <- F.sync(pending.put(id, None))
      _ <- F.async[ClientDispatcherError, Unit] {
        f =>
          s.sendTextFrame(envelope.asJson.printWith(printer))
            .addListener(new GenericFutureListener[Future[Void]] {
              override def operationComplete(future: Future[Void]): Unit = {
                if (future.isSuccess) {
                  f(Right(()))
                } else {
                  f(Left(ClientDispatcherError.UnknownException(future.cause())))
                }
              }
            })
          ()
      }

      p <- trans.ofZio(Promise.make[ClientDispatcherError, ClientResponse[ResponseContext, Json]])

      check = trans.ofZio(for {
        status <- IO.effectTotal(pending.get(id))
        _ <- status match {
          case Some(value) =>
            for {
              responseContext <- ctx.decode(value)
              _ <- p.complete(IO.succeed(ClientResponse(responseContext, value.body)))
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

      out <- new Repeat[F].repeat(check, ClientDispatcherError.TimeoutException(id, methodId), pollingConfig.sleep, 0, pollingConfig.maxAttempts)
    } yield {
      System.err.println(s"HERE: $out")
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
      _ <- F.sync(println(s"sending buzzer response $out"))
      _ <- F.sync(conn.sendTextFrame(AsyncSuccess(Map.empty, out.value, data.id).asJson.printWith(printer)))
    } yield {

    }

    BIORunner[F].unsafeRunSyncAsEither(work)
    ()
  }

  private def handleResponse(value: Json): Unit = {
    for {
      data <- value.as[AsyncSuccess]
      id <- Try(UUID.fromString(data.id.id)).toEither
    } yield {
      pending.put(InvokationId(id.toString), Some(data))
    }

    ()
  }

}



