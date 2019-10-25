package rpcmodel.rt.transport.http.clients.ahc

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

import io.circe.{Json, Printer}
import io.netty.util.concurrent.{Future, GenericFutureListener}
import izumi.functional.bio.BIOAsync
import org.asynchttpclient.netty.ws.NettyWebSocket
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.EnvelopeOut
import zio.clock.Clock
import zio.{DefaultRuntime, IO, Promise, Ref, Schedule, ZIO, ZSchedule}

import scala.util.Try
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.ws.{WebSocket, WebSocketListener, WebSocketUpgradeHandler}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBase, GeneratedServerBaseImpl}
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.servers.AbstractServerHandler
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeIn, InvokationId}
import zio.internal.{Platform, PlatformLive}


class AHCWebsocketClient[ /*F[+_, +_]: BIOAsync,*/ RequestContext, ResponseContext, ServerRequestContext]
(
  client: AsyncHttpClient,
  target: URI,
  printer: Printer,
  ctx: CtxDec[IO, ClientDispatcherError, EnvelopeOut, ResponseContext],
  val dispatchers: Seq[GeneratedServerBaseImpl[ZIO[Clock, +?, +?], ServerRequestContext, Json]],
  override protected val dec: CtxDec[ZIO[Clock, ?, ?], ServerTransportError, EnvelopeIn, ServerRequestContext],

  hook: ClientRequestHook[RequestContext] = ClientRequestHook.Passthrough,
) extends ClientTransport[ZIO[Clock, ?, ?], RequestContext, ResponseContext, Json] with AbstractServerHandler[ZIO[Clock, +?, +?], ServerRequestContext, EnvelopeIn, Json] {

  import io.circe.parser._
  import io.circe.syntax._


  override protected implicit def bioAsync: BIOAsync[ZIO[Clock, +*, +*]] = {
    implicit val clock: Clock.Live.type = Clock.Live

    BIOAsync.BIOAsyncZio
  }

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
            handleResponse(value) match {
              case Right(_) =>
              case Left(_) => // TODO
            }
          }
      }
    }
  }

  val runtime: DefaultRuntime = new DefaultRuntime {
    override val Platform: Platform = PlatformLive.makeDefault().withReportFailure(_ => ())
  }
  private def handleRequest(value: Json): Either[Throwable, Unit] = {
    val work = for {
      data <- IO.fromEither(value.as[EnvelopeIn])
      out <- call(data, data.methodId, data.body)
      conn <- IO.effectTotal(session())
      _ <- IO.effect(println(s"sending buzzer response $out"))
      _ <- IO.effect(conn.sendTextFrame(EnvelopeOut(Map.empty, out.value, data.id).asJson.printWith(printer)))
    } yield {

    }

    runtime.unsafeRunSync(work).toEither
  }

  private def handleResponse(value: Json): Either[Throwable, Unit] = {
    for {
      data <- value.as[EnvelopeOut]
      id <- Try(UUID.fromString(data.id.id)).toEither
    } yield {
      pending.put(InvokationId(id.toString), Some(data))
      ()
    }
  }

  private val pending = new ConcurrentHashMap[InvokationId, Option[EnvelopeOut]]()

  //private lazy val ref = Ref.make[Option[NettyWebSocket]](None)

  override def dispatch(c: RequestContext, methodId: GeneratedServerBase.MethodId, body: Json): ZIO[Clock, ClientDispatcherError, ClientResponse[ResponseContext, Json]] = {
    import zio.duration._
    for {
      s <- IO.effectTotal(session())
      id = InvokationId(UUID.randomUUID().toString)
      envelope = EnvelopeIn(methodId, Map.empty, body, id)
      _ <- IO.effectTotal(pending.put(id, None))
      _ <- IO.effectAsync[ClientDispatcherError, Unit] {
        f =>
          s.sendTextFrame(envelope.asJson.printWith(printer)).addListener(new GenericFutureListener[Future[Void]] {
            override def operationComplete(future: Future[Void]): Unit = {
              if (future.isSuccess) {
                f(IO.unit)
              } else {
                f(IO.fail(ClientDispatcherError.UnknownException(future.cause())))
              }
            }
          })
      }

      p <- Promise.make[ClientDispatcherError, ClientResponse[ResponseContext, Json]]
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
      _ <- check.repeat((Schedule.spaced(100.millis) && ZSchedule.elapsed.whileOutput(_ < 2.seconds)) && Schedule.doUntil(a => a))
      result <- p.poll
      u <- result match {
        case Some(r) => r
        case None => IO.fail(ClientDispatcherError.TimeoutException(id, methodId))
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


