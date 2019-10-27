package server

import java.net.{URI, URL}
import java.util.concurrent.TimeUnit

import io.circe._
import io.undertow.{Handlers, Undertow}
import izumi.functional.bio.BIORunner
import org.asynchttpclient.Dsl._
import org.asynchttpclient.Response
import org.scalatest.WordSpec
import rpcmodel.generated.ICalc.ZeroDivisionError
import rpcmodel.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.clients.ahc.{AHCHttpClient, AHCWebsocketClient}
import rpcmodel.rt.transport.http.servers.shared.Envelopes.{AsyncRequest, AsyncResponse}
import rpcmodel.rt.transport.http.servers.shared.{BasicTransportErrorHandler, MethodIdExtractor, PollingConfig}
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.http.servers.undertow.ws.model.{BuzzerRequestContext, WsConnection, WsServerInRequestContext}
import rpcmodel.rt.transport.http.servers.undertow.ws.{SessionManager, SessionMetaProvider, WsBuzzerTransport}
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
        assert(runtime.unsafeRunSync(client.div(C2SReqestClientCtx(), 6, 2)) == Exit.Success(3))

        val negative = for {
          res <- client.div(C2SReqestClientCtx(), 6, 0)
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
          a1 <- wsClient.div(C2SReqestClientCtx(), 6, 2)
          _ <- IO.effect(assert(a1 == 3))
          _ <- IO.effect(server.stop())
          a2 <- wsClient.div(C2SReqestClientCtx(), 8, 2).sandbox.fold(_ => -1, _ => -2)
          _ <- IO.effect(assert(a2 == -1))
          _ <- IO.effect(server.start())
          a3 <- wsClient.div(C2SReqestClientCtx(), 15, 3)
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
                val buzzertransport = new WsBuzzerTransport[IO, CustomWsMeta, C2SResponseClientCtx](
                  PollingConfig(FiniteDuration(100, TimeUnit.MILLISECONDS), 20),
                  b,
                  printer,
                  new ContextProvider[IO, ClientDispatcherError, AsyncResponse, C2SResponseClientCtx] {
                    override def decode(c: AsyncResponse): IO[ClientDispatcherError, C2SResponseClientCtx] = IO.succeed(C2SResponseClientCtx())
                  }
                )

                new GeneratedCalcClientDispatcher(
                  codecs,
                  buzzertransport,
                )
            }


            ZIO.traverse(clients) {
              c =>
                c.div(BuzzerRequestContext.empty, 90, 3)
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

  protected def makeClient(): GeneratedCalcClientDispatcher[IO, C2SReqestClientCtx, C2SResponseClientCtx, Json] = {
    val responseCtxProvider = new ContextProvider[IO, ClientDispatcherError, Response, C2SResponseClientCtx] {
      override def decode(c: Response): IO[ClientDispatcherError, C2SResponseClientCtx] = IO.succeed(C2SResponseClientCtx())
    }

    val transport = new AHCHttpClient[IO, C2SReqestClientCtx, C2SResponseClientCtx](
      asyncHttpClient(config()),
      new URL("http://localhost:8080/http"),
      printer,
      responseCtxProvider,
    )

    new GeneratedCalcClientDispatcher(
      codecs,
      transport,
    )
  }

  protected def makeWsClient(): GeneratedCalcClientDispatcher[IO, C2SReqestClientCtx, C2SResponseClientCtx, Json] = {
    val serverResponseCtxProvider = new ContextProvider[IO, ClientDispatcherError, AsyncResponse, C2SResponseClientCtx] {
      override def decode(c: AsyncResponse): IO[ClientDispatcherError, C2SResponseClientCtx] = IO.succeed(C2SResponseClientCtx())
    }


    val buzzerCtxProvider = new ContextProvider[IO, ServerTransportError, AsyncRequest, S2CReqestClientCtx] {
      override def decode(c: AsyncRequest): IO[ServerTransportError, S2CReqestClientCtx] = {
        IO.succeed(S2CReqestClientCtx())
      }
    }


    val transport = new AHCWebsocketClient[IO, C2SReqestClientCtx, C2SResponseClientCtx, S2CReqestClientCtx](
      asyncHttpClient(config()),
      new URI("ws://localhost:8080/ws"),
      PollingConfig(FiniteDuration(100, TimeUnit.MILLISECONDS), 20),
      printer,
      serverResponseCtxProvider,
      buzzerCtxProvider,
      dispatchers,
    )

    new GeneratedCalcClientDispatcher(
      codecs,
      transport,
    )
  }

  protected def makeServer(): (Undertow, SessionManager[IO, CustomWsMeta]) = {
    val dispatchers = this.dispatchers[C2SRequestServerCtx]

    def makeHttpHandler: HttpServerHandler[IO, C2SRequestServerCtx, Nothing] = {
      val serverctxdec = new ContextProvider[IO, ServerTransportError, HttpRequestContext, C2SRequestServerCtx] {
        override def decode(c: HttpRequestContext): IO[ServerTransportError, C2SRequestServerCtx] = {
          IO.succeed(C2SRequestServerCtx(c.exchange.getSourceAddress.toString, c.headers))
        }
      }
      new HttpServerHandler[IO, C2SRequestServerCtx, Nothing](
        dispatchers,
        serverctxdec,
        printer,
        MethodIdExtractor.TailImpl,
        BasicTransportErrorHandler.withoutDomain
      )
    }


    def makeWsHandler: WebsocketServerHandler[IO, CustomWsMeta, C2SRequestServerCtx, Nothing] = {
      val wsctxdec = new ContextProvider[IO, ServerTransportError, WsServerInRequestContext, C2SRequestServerCtx] {
        override def decode(c: WsServerInRequestContext): IO[ServerTransportError, C2SRequestServerCtx] = {
          IO.succeed(C2SRequestServerCtx(c.ctx.channel.getSourceAddress.toString, c.envelope.headers))
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

      new WebsocketServerHandler[IO, CustomWsMeta, C2SRequestServerCtx, Nothing](
        wsctxdec,
        dispatchers,
        printer,
        BasicTransportErrorHandler.withoutDomain,
        sessionMetaProvider,
      )
    }

    val httpHandler: HttpServerHandler[IO, C2SRequestServerCtx, Nothing] = makeHttpHandler
    val wsHandler: WebsocketServerHandler[IO, CustomWsMeta, C2SRequestServerCtx, Nothing] = makeWsHandler

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

