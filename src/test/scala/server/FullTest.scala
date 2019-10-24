package server

import java.net.URL

import io.circe._
import io.undertow.{Handlers, Undertow}
import izumi.functional.bio.BIORunner
import org.asynchttpclient.Response
import org.scalatest.WordSpec
import rpcmodel.generated.ICalc.ZeroDivisionError
import rpcmodel.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher, ICalc}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.GeneratedServerBase._
import rpcmodel.rt.transport.dispatch.{ClientHook, CtxDec}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerTransportError}
import rpcmodel.rt.transport.http.clients.ahc.AHCClient
import rpcmodel.rt.transport.http.servers.undertow.{HttpRequestContext, HttpServerHandler, WSRequestContext, WsHandler}
import rpcmodel.rt.transport.http.servers.{BasicTransportErrorHandler, MethodIdExtractor}
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.clock.Clock
import zio.internal.{Platform, PlatformLive}
import zio._


class FullTest extends WordSpec {
  private val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()
  private implicit val clock: Clock.Live.type = Clock.Live
  private val printer: Printer = Printer.spaces2
  private val runtime = new DefaultRuntime {
    override val Platform: Platform = PlatformLive.makeDefault().withReportFailure(_ => ())
  }

  def main(args: Array[String]): Unit = {
    println("Server...")
    val server = makeServer()
    server.start()

    println("Client...")
    val client = makeClient()
    println(runtime.unsafeRunSync(client.div(6, 2)))
    println(runtime.unsafeRunSync(client.div(6, 0)))
  }


  "transport" should {
    "support method calls" in {
      val server = makeServer()
      try {
        server.start()
        val client = makeClient()
        assert(runtime.unsafeRunSync(client.div(6, 2)) == Exit.Success(3))

        val negative = for {
          res <- client.div(6, 0)
            .catchAll((_: ZeroDivisionError) => IO("Got error"))
        } yield {
          res
        }

        assert(runtime.unsafeRunSync(negative).toEither  == Right("Got error"))


      } finally {
        server.stop()
      }
    }
  }

  private def makeClient(): GeneratedCalcClientDispatcher[IO, CustomClientCtx, Json] = {
    import org.asynchttpclient.Dsl._

    val fakeTransport = new AHCClient[IO, CustomClientCtx](asyncHttpClient(config()), new URL("http://localhost:8080/http"), printer, new CtxDec[IO, ClientDispatcherError, Response, CustomClientCtx] {
      override def decode(c: Response): IO[ClientDispatcherError, CustomClientCtx] = IO.succeed(CustomClientCtx())
    })


    val hook = new ClientHook[IO, CustomClientCtx, Json] {
      override def onDecode[A: IRTCodec[*, Json]](res: ClientResponse[CustomClientCtx, Json], next: ClientResponse[CustomClientCtx, Json] => IO[ClientDispatcherError, A]): IO[ClientDispatcherError, A] = {
        println(s"Client hook: ${res.value}")
        super.onDecode(res, next)
      }
    }
    new GeneratedCalcClientDispatcher[IO, CustomClientCtx, Json](
      codecs,
      fakeTransport,
      hook
    )
  }

  private def makeServer(): Undertow = {
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

    implicit val runner: BIORunner.ZIORunner = BIORunner.createZIO(PlatformLive.makeDefault()) //.withReportFailure(_ => ())

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
    val handler2 = new WsHandler[IO, CustomServerCtx, Nothing](
      wsctxdec,
      dispatchers,
      printer,
      BasicTransportErrorHandler.withoutDomain
    )

    Undertow
      .builder()
      .addHttpListener(8080, "localhost")
      .setHandler(
        Handlers.path()
          .addPrefixPath("http", handler1)
          .addExactPath("ws", Handlers.websocket(handler2)),
      )
      .build()

  }
}
