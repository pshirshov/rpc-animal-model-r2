package rpcmodel.rt.transport.http.clients.ahc

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import io.circe.{Json, Printer}
import io.netty.util.concurrent.{Future, GenericFutureListener}
import izumi.functional.bio.BIO._
import izumi.functional.bio.{BIOAsync, BIOExit, BIORunner, BIOTransZio}
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.netty.ws.NettyWebSocket
import org.asynchttpclient.ws.{WebSocket, WebSocketListener, WebSocketUpgradeHandler}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBase, GeneratedServerBaseImpl}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.servers.AbstractServerHandler
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeIn, EnvelopeOut, InvokationId}
import zio._
import zio.clock.Clock

import scala.util.Try

class AHCWebsocketClient[F[+_, +_]: BIOAsync : BIOTransZio : BIORunner, RequestContext, ResponseContext, ServerRequestContext]
(
  client: AsyncHttpClient,
  target: URI,
  printer: Printer,
  ctx: CtxDec[IO, ClientDispatcherError, EnvelopeOut, ResponseContext],
  val dispatchers: Seq[GeneratedServerBaseImpl[F, ServerRequestContext, Json]],
  override protected val dec: CtxDec[F, ServerTransportError, EnvelopeIn, ServerRequestContext],

  hook: ClientRequestHook[RequestContext] = ClientRequestHook.Passthrough,
) extends ClientTransport[F, RequestContext, ResponseContext, Json] with AbstractServerHandler[F, ServerRequestContext, EnvelopeIn, Json] {

  import io.circe.parser._
  import io.circe.syntax._


  override protected def bioAsync: BIOAsync[F] = implicitly

  private val sess = new AtomicReference[NettyWebSocket](null)

  private def session(): NettyWebSocket = {
    Option(sess.get()) match {
      case Some(value) if value.isOpen =>
        value
      case _ =>
        this.synchronized {
          Option(sess.get()) match {
            case Some(value) if value.isOpen =>
              value
            case _ =>
              val conn = prepare()
              sess.set(conn)
              conn
          }
        }
    }
  }

  private val listener = new WebSocketListener() {
    override def onOpen(websocket: WebSocket): Unit = {
      //logger.debug(s"WS connection open: $websocket")
    }

    override def onClose(websocket: WebSocket, code: Int, reason: String): Unit = {
      //logger.debug(s"WS connection closed: $websocket, $code, $reason")
    }

    override def onError(t: Throwable): Unit = {
      //logger.debug(s"WS connection errored: $t")
    }

    override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit = {
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
  }

  private def handleRequest(value: Json): Unit = {
    val work = for {
      data <- F.fromEither(value.as[EnvelopeIn])
      out <- call(data, data.methodId, data.body)
      conn <- F.sync(session())
      _ <- F.sync(println(s"sending buzzer response $out"))
      _ <- F.sync(conn.sendTextFrame(EnvelopeOut(Map.empty, out.value, data.id).asJson.printWith(printer)))
    } yield {

    }

    BIORunner[F].unsafeRunSyncAsEither(work)
    ()
  }

  private def handleResponse(value: Json): Unit = {
    for {
      data <- value.as[EnvelopeOut]
      id <- Try(UUID.fromString(data.id.id)).toEither
    } yield {
      pending.put(InvokationId(id.toString), Some(data))
      ()
    }
  }

  private val pending = new ConcurrentHashMap[InvokationId, Option[EnvelopeOut]]()

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    import zio.duration._
    val trans = implicitly[BIOTransZio[F]]
    for {
      s <- F.sync(session())
      id = InvokationId(UUID.randomUUID().toString)
      envelope = EnvelopeIn(methodId, Map.empty, body, id)
      _ <- F.sync(pending.put(id, None))
      _ <- F.async[ClientDispatcherError, Unit] {
        f =>
          s.sendTextFrame(envelope.asJson.printWith(printer)).addListener(new GenericFutureListener[Future[Void]] {
            override def operationComplete(future: Future[Void]): Unit = {
              if (future.isSuccess) {
                f(Right(()))
              } else {
                f(Left(ClientDispatcherError.UnknownException(future.cause())))
              }
            }
          })
      }

      p <- trans.ofZio(Promise.make[ClientDispatcherError, ClientResponse[ResponseContext, Json]])
      check = for {
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
      } yield {
        done
      }
      _ <- trans.ofZio(check.repeat((Schedule.spaced(100.millis) && ZSchedule.elapsed.whileOutput(_ < 2.seconds)) && Schedule.doUntil(a => a)).provide(Clock.Live))
      result <- trans.ofZio(p.poll)
      u <- result match {
        case Some(r) => trans.ofZio(r)
        case None => F.fail(ClientDispatcherError.TimeoutException(id, methodId))
      }
    } yield {
      System.err.println(s"HERE: $u")
      u
    }
  }

  private def prepare(): NettyWebSocket = {
    import scala.collection.JavaConverters._

    println("Connection...")
    client.prepareGet(target.toString)
      .execute(new WebSocketUpgradeHandler(List(listener).asJava))
      .get()
  }

}


