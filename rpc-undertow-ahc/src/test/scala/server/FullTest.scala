package server

import io.circe._
import io.undertow.{Handlers, Undertow}
import izumi.functional.bio._
import org.asynchttpclient.BoundRequestBuilder
import org.scalatest.WordSpec
import server.fixtures.generated.ICalc.ZeroDivisionError
import server.fixtures.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.IRTBuilder
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.client.ClientRequestHook
import rpcmodel.rt.transport.http.clients.ahc._
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.http.servers.undertow.ws.model.WsServerInRequestContext
import rpcmodel.rt.transport.http.servers.undertow.ws.{IdentifiedRequestContext, SessionManager, SessionMetaProvider}
import rpcmodel.rt.transport.http.servers.undertow.{HttpServerHandler, WebsocketServerHandler}
import server.fixtures.user.impl.CalcServerImpl
import zio._
import zio.clock.Clock
import zio.internal.{Platform, PlatformLive}

final case class IncomingPushClientCtx()

object TestMain extends FullTest {
  def main(args: Array[String]): Unit = {
    val server = makeServer()._1
    server.start()
    val client = makeClient(true)
    println(runtime.unsafeRunSync(client.div(C2SOutgoingCtx(), 6, 2)))

    //    println(runtime.unsafeRunSync(wsClient.div(CustomClientCtx(), 6, 2)))
    //    println(runtime.unsafeRunSync(wsClient.div(CustomClientCtx(), 6, 0)))
  }
}

class FullTest extends WordSpec {
  protected val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()

  protected def dispatchers[Ctx]: Seq[GeneratedCalcServerDispatcher[IO, Ctx, Json]] = {
    val server = new CalcServerImpl[IO, Ctx]
    Seq(new GeneratedCalcServerDispatcher(server, codecs))
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
        val client = makeClient(false)
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

    "support rest mappings" in withServer {
      (_, _) =>
        val client = makeClient(true)
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
                val buzzertransport = IRTBuilder().makeWsBuzzerTransport(b, ClientRequestHook.forCtx[IdentifiedRequestContext[OutgoingPushServerCtx]].passthrough)

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

  protected def makeClient(rest: Boolean): GeneratedCalcClientDispatcher[IO, C2SOutgoingCtx, Json] = {
    val uri = "http://localhost:8080/http"
    val hook = if (rest) {
      val specs = dispatchers[Nothing].flatMap(d => d.specs.toSeq).toMap
      new RestRequestHook[IO, C2SOutgoingCtx](specs)
    } else {
      ClientRequestHook.forCtx[AHCClientContext[C2SOutgoingCtx]].passthrough[BoundRequestBuilder]
    }

    new GeneratedCalcClientDispatcher(
      codecs,
      IRTBuilder().makeHttpClient(uri, hook),
    )
  }

  protected def makeWsClient(): GeneratedCalcClientDispatcher[IO, C2SOutgoingCtx, Json] = {
    val transport = IRTBuilder(dispatchers[IncomingPushClientCtx]).makeWsClient(
      target = "ws://localhost:8080/ws",
      buzzerContextProvider = ContextProvider.forF[IO].const(IncomingPushClientCtx()),
      hook = ClientRequestHook.forCtx[IdentifiedRequestContext[C2SOutgoingCtx]].passthrough
    )

    new GeneratedCalcClientDispatcher(codecs, transport)
  }

  protected def makeServer(): (Undertow, SessionManager[IO, CustomWsMeta]) = {
    val dispatchers = this.dispatchers[IncomingServerCtx]

    def makeHttpHandler: HttpServerHandler[IO, IncomingServerCtx, Nothing] = {
      IRTBuilder(dispatchers).makeHttpRestServer(
        serverContextProvider = ContextProvider.forF[IO].pure((w: HttpRequestContext) => IncomingServerCtx(w.exchange.getSourceAddress.toString, w.headers)),
      )
    }

    def makeWsHandler: WebsocketServerHandler[IO, CustomWsMeta, IncomingServerCtx, Nothing] = {
      IRTBuilder(dispatchers).makeWsServer(
        wsServerContextProvider = ContextProvider.forF[IO].pure((w: WsServerInRequestContext) => IncomingServerCtx(w.ctx.channel.getSourceAddress.toString, w.envelope.headers)),
        sessionMetaProvider = SessionMetaProvider.simple {
          case (_, Some(prev), Some(req)) =>
            CustomWsMeta(prev.history ++ List(req.id.id))
          case (_, _, _) =>
            CustomWsMeta(List())
        },
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

