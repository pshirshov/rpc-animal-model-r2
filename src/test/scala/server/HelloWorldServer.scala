package server

import io.circe.{Json, Printer}
import io.undertow.{Handlers, Undertow}
import izumi.functional.bio.BIORunner
import rpcmodel.generated.{GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.errors.ServerDispatcherError
import rpcmodel.rt.transport.http.{BasicTransportErrorHandler, HttpServerHandler, MethodIdExtractor, WsHandler}
import rpcmodel.user.impl.CalcServerImpl
import rpcmodel.rt.transport.http.WsEnvelope.WsResponseContext
import zio._
import zio.clock.Clock
import zio.internal.PlatformLive



object HelloWorldServer {

  def main(args: Array[String]): Unit = {
    val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()
    val server = new CalcServerImpl[IO, CustomServerCtx]
    val serverctxdec = new CtxDec[IO, ServerDispatcherError, Map[String, Seq[String]], CustomServerCtx] {
      override def decode(c: Map[String, Seq[String]]): IO[ServerDispatcherError, CustomServerCtx] = IO.succeed(CustomServerCtx())
    }
    val serverDispatcher = new GeneratedCalcServerDispatcher[IO, CustomServerCtx, Map[String, Seq[String]], Json](
      server,
      serverctxdec,
      codecs
    )

    val dispatchers = Seq(serverDispatcher)
    val printer = Printer.spaces2

    implicit val runner: BIORunner.ZIORunner = BIORunner.createZIO(PlatformLive.makeDefault()) //.withReportFailure(_ => ())
    implicit val clock: Clock.Live.type = Clock.Live

    val handler1 = new HttpServerHandler[IO, CustomServerCtx, Nothing](
      dispatchers,
      printer,
      new MethodIdExtractor.TailImpl(),
      BasicTransportErrorHandler.withoutDomain
    )

    val handler2 = new WsHandler[IO, CustomServerCtx, Nothing](
      dispatchers,
      printer,
      BasicTransportErrorHandler.withoutDomain
    )

    val ut: Undertow = Undertow
      .builder()
      .addHttpListener(8080, "localhost")
      .setHandler(
        Handlers.path()
          .addPrefixPath("http", handler1)
          .addExactPath("ws", Handlers.websocket(handler2)),
      )
      .build()
    ut.start()
  }

}
