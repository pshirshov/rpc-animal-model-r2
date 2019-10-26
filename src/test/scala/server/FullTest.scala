package server

import java.net.{URI, URL}

import io.circe._
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.spi.WebSocketHttpExchange
import io.undertow.{Handlers, Undertow}
import izumi.functional.bio.BIORunner
import org.asynchttpclient.Response
import org.scalatest.WordSpec
import rpcmodel.generated.ICalc.ZeroDivisionError
import rpcmodel.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.client.ClientHook
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase._
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.clients.ahc.{AHCHttpClient, AHCWebsocketClient}
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeIn, EnvelopeOut}
import rpcmodel.rt.transport.http.servers.undertow.{HttpRequestContext, HttpServerHandler, SessionManager, SessionMetaProvider, WSRequestContext, WsBuzzerTransport, WsEnvelope, WsHandler}
import rpcmodel.rt.transport.http.servers.{BasicTransportErrorHandler, MethodIdExtractor}
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.clock.Clock
import zio.internal.{Platform, PlatformLive}

object TestMain extends FullTest {
  def main(args: Array[String]): Unit = {


    //    println(runtime.unsafeRunSync(wsClient.div(CustomClientCtx(), 6, 2)))
    //    println(runtime.unsafeRunSync(wsClient.div(CustomClientCtx(), 6, 0)))
  }
}

class FullTest extends WordSpec {
  protected val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()
  protected val printer: Printer = Printer.spaces2

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
        assert(runtime.unsafeRunSync(client.div(CustomClientCtx(), 6, 2)) == Exit.Success(3))

        val negative = for {
          res <- client.div(CustomClientCtx(), 6, 0)
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
          a1 <- wsClient.div(CustomClientCtx(), 6, 2)
          _ <- IO.effect(assert(a1 == 3))
          _ <- IO.effect(server.stop())
          a2 <- wsClient.div(CustomClientCtx(), 8, 2).sandbox.fold(_ => -1, _ => -2)
          _ <- IO.effect(assert(a2 == -1))
          _ <- IO.effect(server.start())
          a3 <- wsClient.div(CustomClientCtx(), 15, 3)
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
          a1 <- wsClient.div(CustomClientCtx(), 6, 2)
          b = sessman.filterSessions(_ => true)
          res <- {

            val clients = b.map {
              b =>
                println(s"Buzzing ${b.id} ${b.meta}...")
                val buzzertransport = new WsBuzzerTransport[IO, CustomWsMeta, CustomClientCtx, CustomClientCtx](b, printer, new CtxDec[IO, ClientDispatcherError, EnvelopeOut, CustomClientCtx] {
                  override def decode(c: EnvelopeOut): IO[ClientDispatcherError, CustomClientCtx] = IO.succeed(CustomClientCtx())
                })

                new GeneratedCalcClientDispatcher[IO, CustomClientCtx, CustomClientCtx, Json](
                  codecs,
                  buzzertransport,
                )
            }


            ZIO.traverse(clients) {
              c =>
                c.div(CustomClientCtx(), 90, 3)
            }
          }
        } yield {
          res
        }

        val result = runtime.unsafeRunSync(test)
        println(result)
        assert(result.succeeded && result.toEither ==  Right(List(30)))
        ()
    }
  }

  protected def makeClient(): GeneratedCalcClientDispatcher[IO, CustomClientCtx, CustomClientCtx, Json] = {
    import org.asynchttpclient.Dsl._

    val fakeTransport = new AHCHttpClient[IO, CustomClientCtx, CustomClientCtx](asyncHttpClient(config()), new URL("http://localhost:8080/http"), printer, new CtxDec[IO, ClientDispatcherError, Response, CustomClientCtx] {
      override def decode(c: Response): IO[ClientDispatcherError, CustomClientCtx] = IO.succeed(CustomClientCtx())
    })


    val hook = new ClientHook[IO, CustomClientCtx, Json] {
      override def onDecode[A: IRTCodec[*, Json]](res: ClientResponse[CustomClientCtx, Json], next: ClientResponse[CustomClientCtx, Json] => IO[ClientDispatcherError, A]): IO[ClientDispatcherError, A] = {
        println(s"Client hook: ${res.value}")
        super.onDecode(res, next)
      }
    }
    new GeneratedCalcClientDispatcher[IO, CustomClientCtx, CustomClientCtx, Json](
      codecs,
      fakeTransport,
      hook
    )
  }

  protected def makeWsClient(): GeneratedCalcClientDispatcher[IO, CustomClientCtx, CustomClientCtx, Json] = {
    import org.asynchttpclient.Dsl._

    val dec = new CtxDec[IO, ClientDispatcherError, EnvelopeOut, CustomClientCtx] {
      override def decode(c: EnvelopeOut): IO[ClientDispatcherError, CustomClientCtx] = IO.succeed(CustomClientCtx())
    }

    val server = new CalcServerImpl[IO, CustomClientCtx]
    val serverDispatcher = new GeneratedCalcServerDispatcher[IO, CustomClientCtx, Json](
      server,
      codecs,
    )

    val dispatchers = Seq(serverDispatcher)
    val serverctxdec = new CtxDec[IO, ServerTransportError, EnvelopeIn, CustomClientCtx] {
      override def decode(c: EnvelopeIn): IO[ServerTransportError, CustomClientCtx] = {
        IO.succeed(CustomClientCtx())
      }
    }

    val fakeTransport = new AHCWebsocketClient[IO, CustomClientCtx, CustomClientCtx, CustomClientCtx](
      asyncHttpClient(config()),
      new URI("ws://localhost:8080/ws"),
      printer,
      dec,
      dispatchers,
      serverctxdec,
    )


    val hook = new ClientHook[IO, CustomClientCtx, Json] {}
    new GeneratedCalcClientDispatcher[IO, CustomClientCtx, CustomClientCtx, Json](
      codecs,
      fakeTransport,
      hook
    )
  }

  protected def makeServer() = {
    val server = new CalcServerImpl[IO, CustomServerCtx]
    val serverctxdec = new CtxDec[IO, ServerTransportError, HttpRequestContext, CustomServerCtx] {
      override def decode(c: HttpRequestContext): IO[ServerTransportError, CustomServerCtx] = {
        IO.succeed(CustomServerCtx(c.exchange.getSourceAddress.toString, c.headers))
      }
    }
    val serverDispatcher = new GeneratedCalcServerDispatcher[IO, CustomServerCtx, Json](
      server,
      codecs,
    )

    val dispatchers = Seq(serverDispatcher)


    val handler1 = new HttpServerHandler[IO, CustomServerCtx, Nothing](
      dispatchers,
      serverctxdec,
      printer,
      MethodIdExtractor.TailImpl,
      BasicTransportErrorHandler.withoutDomain
    )


    val wsctxdec = new CtxDec[IO, ServerTransportError, WSRequestContext, CustomServerCtx] {
      override def decode(c: WSRequestContext): IO[ServerTransportError, CustomServerCtx] = {
        IO.succeed(CustomServerCtx(c.channel.getSourceAddress.toString, c.envelope.headers))
      }
    }

    val sessionManager = new SessionManager[IO, CustomWsMeta]
    val sessionMetaProvider = new SessionMetaProvider[CustomWsMeta] {
      override def extractInitial(exchange: WebSocketHttpExchange, channel: WebSocketChannel): CustomWsMeta = CustomWsMeta(List())

      override def extract(exchange: WebSocketHttpExchange, channel: WebSocketChannel, previous: CustomWsMeta, envelopeIn: WsEnvelope.EnvelopeIn): Option[CustomWsMeta] = Some(CustomWsMeta(previous.history ++ List(envelopeIn.id.id)))
    }
    val handler2 = new WsHandler[IO, CustomWsMeta, CustomServerCtx, Nothing](
      wsctxdec,
      dispatchers,
      printer,
      BasicTransportErrorHandler.withoutDomain,
      sessionManager,
      sessionMetaProvider,
    )

    val s = Undertow
      .builder()
      .addHttpListener(8080, "localhost")
      .setHandler(
        Handlers.path()
          .addPrefixPath("http", handler1)
          .addExactPath("ws", Handlers.websocket(handler2)),
      )
      .build()


    (s, sessionManager)
  }
}
