package server

import java.net.{URI, URL}
import java.util.concurrent.TimeUnit

import io.circe._
import io.undertow.{Handlers, Undertow}
import izumi.functional.bio.{BIORunner, Clock2, Entropy2}
import izumi.functional.mono
import izumi.functional.mono.Entropy
import org.asynchttpclient.Dsl._
import org.scalatest.WordSpec
import rpcmodel.generated.ICalc.ZeroDivisionError
import rpcmodel.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.clients.ahc.{AHCHttpClient, AHCWebsocketClient, ClientRequestHook}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.AsyncRequest
import rpcmodel.rt.transport.http.servers.shared.{BasicTransportErrorHandler, MethodIdExtractor, PollingConfig}
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{WsConnection, WsServerInRequestContext}
import rpcmodel.rt.transport.http.servers.undertow.ws.{RuntimeErrorHandler, SessionManager, SessionMetaProvider, WsBuzzerTransport}
import rpcmodel.rt.transport.http.servers.undertow.{HttpServerHandler, WebsocketServerHandler}
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.clock.Clock
import zio.internal.{Platform, PlatformLive}

import scala.concurrent.duration.FiniteDuration

object TestMain extends FullTest {
  def main(args: Array[String]): Unit = {


    //    println(runtime.unsafeRunSync(wsClient.div(CustomClientCtx(), 6, 2)))
    //    println(runtime.unsafeRunSync(wsClient.div(CustomClientCtx(), 6, 0)))
  }
}

class FullTest extends WordSpec {
  protected val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()
  protected val printer: Printer = Printer.spaces2
  protected val clock2: Clock2[IO] = izumi.functional.mono.Clock.fromImpure[IO[Nothing, ?]](new mono.Clock.Standard())
  protected val entropy2: Entropy2[IO] = izumi.functional.mono.Entropy.fromImpure[IO[Nothing, ?]](Entropy.Standard)

  protected def dispatchers[T]: Seq[GeneratedCalcServerDispatcher[IO, T, Json]] = {
    val server = new CalcServerImpl[IO, T]
    val serverDispatcher = new GeneratedCalcServerDispatcher[IO, T, Json](
      server,
      codecs,
    )
    Seq(serverDispatcher)
  }

  protected implicit val clock: Clock.Live.type = Clock.Live
  protected val runtime: DefaultRuntime = new DefaultRuntime {
    override val Platform: Platform = PlatformLive.makeDefault().withReportFailure(_ => ())
  }
  implicit val runner: BIORunner.ZIORunner = BIORunner.createZIO(PlatformLive.makeDefault()) //.withReportFailure(_ => ())


  def withServer(test: (Undertow, SessionManager[IO, CustomWsMeta]) => Unit): Unit = {
    val (server, sessman) = makeServer()
    try {
      server.start()
      test(server, sessman)
    } finally {
      server.stop()
    }
  }

  "transport" should {
    "support http calls" in withServer {
      (_, _) =>
        val client = makeClient()
        assert(runtime.unsafeRunSync(client.div(C2SOutgoingCtx(), 6, 2)) == Exit.Success(3))

        val negative = for {
          res <- client.div(C2SOutgoingCtx(), 6, 0)
            .catchAll((_: ZeroDivisionError) => IO("Got error"))
        } yield {
          res
        }

        assert(runtime.unsafeRunSync(negative).toEither == Right("Got error"))
        ()

    }

    "support websocket calls" in withServer {
      (server, _) =>
        val wsClient = makeWsClient()
        val test = for {
          a1 <- wsClient.div(C2SOutgoingCtx(), 6, 2)
          _ <- IO.effect(assert(a1 == 3))
          _ <- IO.effect(server.stop())
          a2 <- wsClient.div(C2SOutgoingCtx(), 8, 2).sandbox.fold(_ => -1, _ => -2)
          _ <- IO.effect(assert(a2 == -1))
          _ <- IO.effect(server.start())
          a3 <- wsClient.div(C2SOutgoingCtx(), 15, 3)
          _ <- IO.effect(assert(a3 == 5))
        } yield {
          (a1, a2, a3)
        }

        val result = runtime.unsafeRunSync(test)
        assert(result.succeeded && result.toEither == Right((3, -1, 5)))
        ()
    }

    "support buzzer calls" in withServer {
      (_, sessman) =>
        val wsClient = makeWsClient()
        val test = for {
          _ <- wsClient.transport.connect()
          b = sessman.filterSessions(_ => true)
          res <- {

            val clients = b.map {
              b =>
                val buzzertransport = new WsBuzzerTransport(
                  PollingConfig(FiniteDuration(100, TimeUnit.MILLISECONDS), 20),
                  b,
                  printer,
                  ClientRequestHook.forCtx[OutgoingPushServerCtx].passthrough,
                  entropy2,
                )

                new GeneratedCalcClientDispatcher(
                  codecs,
                  buzzertransport,
                )
            }


            ZIO.traverse(clients) {
              c =>
                c.div(OutgoingPushServerCtx(), 90, 3)
            }
          }
        } yield {
          res
        }

        val result = runtime.unsafeRunSync(test)
        assert(result.succeeded && result.toEither == Right(List(30)))
        ()
    }
  }

  protected def makeClient(): GeneratedCalcClientDispatcher[IO, C2SOutgoingCtx, Json] = {
    val transport = new AHCHttpClient[IO, C2SOutgoingCtx](
      asyncHttpClient(config()),
      new URL("http://localhost:8080/http"),
      printer,
      ClientRequestHook.forCtx[C2SOutgoingCtx].passthrough,
    )

    new GeneratedCalcClientDispatcher(
      codecs,
      transport,
    )
  }

  protected def makeWsClient(): GeneratedCalcClientDispatcher[IO, C2SOutgoingCtx, Json] = {
    val buzzerCtxProvider = new ContextProvider[IO, ServerTransportError, AsyncRequest, IncomingPushClientCtx] {
      override def decode(c: AsyncRequest): IO[ServerTransportError, IncomingPushClientCtx] = {
        IO.succeed(IncomingPushClientCtx())
      }
    }


    val transport = new AHCWebsocketClient(
      asyncHttpClient(config()),
      new URI("ws://localhost:8080/ws"),
      PollingConfig(FiniteDuration(100, TimeUnit.MILLISECONDS), 20),
      printer,
      ClientRequestHook.forCtx[C2SOutgoingCtx].passthrough,
      buzzerCtxProvider,
      dispatchers[IncomingPushClientCtx],
      RuntimeErrorHandler.print,
      entropy2,
      BasicTransportErrorHandler.withoutDomain,
    )

    new GeneratedCalcClientDispatcher(
      codecs,
      transport,
    )
  }

  protected def makeServer(): (Undertow, SessionManager[IO, CustomWsMeta]) = {
    val dispatchers = this.dispatchers[IncomingServerCtx]

    def makeHttpHandler: HttpServerHandler[IO, IncomingServerCtx, Nothing] = {
      val serverctxdec = new ContextProvider[IO, ServerTransportError, HttpRequestContext, IncomingServerCtx] {
        override def decode(c: HttpRequestContext): IO[ServerTransportError, IncomingServerCtx] = {
          IO.succeed(IncomingServerCtx(c.exchange.getSourceAddress.toString, c.headers))
        }
      }
      new HttpServerHandler[IO, IncomingServerCtx, Nothing](
        dispatchers,
        serverctxdec,
        printer,
        MethodIdExtractor.TailImpl,
        BasicTransportErrorHandler.withoutDomain,
        RuntimeErrorHandler.print,
      )
    }


    def makeWsHandler: WebsocketServerHandler[IO, CustomWsMeta, IncomingServerCtx, Nothing] = {
      val wsctxdec = new ContextProvider[IO, ServerTransportError, WsServerInRequestContext, IncomingServerCtx] {
        override def decode(c: WsServerInRequestContext): IO[ServerTransportError, IncomingServerCtx] = {
          IO.succeed(IncomingServerCtx(c.ctx.channel.getSourceAddress.toString, c.envelope.headers))
        }
      }

      val sessionMetaProvider = new SessionMetaProvider[CustomWsMeta] {
        override def extractInitial(ctx: WsConnection): CustomWsMeta = {
          CustomWsMeta(List())
        }
        override def extract(ctx: WsConnection, previous: CustomWsMeta, envelopeIn: AsyncRequest): Option[CustomWsMeta] = {
          Some(CustomWsMeta(previous.history ++ List(envelopeIn.id.id)))
        }
      }

      new WebsocketServerHandler(
        wsctxdec,
        dispatchers,
        printer,
        BasicTransportErrorHandler.withoutDomain,
        sessionMetaProvider,
        RuntimeErrorHandler.print,
        clock2,
        Entropy.Standard,
      )
    }

    val httpHandler: HttpServerHandler[IO, IncomingServerCtx, Nothing] = makeHttpHandler
    val wsHandler: WebsocketServerHandler[IO, CustomWsMeta, IncomingServerCtx, Nothing] = makeWsHandler

    val s = Undertow
      .builder()
      .addHttpListener(8080, "localhost")
      .setHandler(
        Handlers.path()
          .addPrefixPath("http", httpHandler)
          .addExactPath("ws", Handlers.websocket(wsHandler)),
      )
      .build()


    (s, wsHandler.sessionManager)
  }
}

