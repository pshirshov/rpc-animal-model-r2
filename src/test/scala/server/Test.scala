package server

import io.circe._
import org.scalatest.WordSpec
import rpcmodel.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.dispatch.GeneratedServerBase._
import rpcmodel.rt._
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.{ClientHook, ClientTransport, CtxDec, GeneratedServerBase}
import rpcmodel.rt.transport.errors.ClientDispatcherError.ServerError
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerDispatcherError}
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.internal.{Platform, PlatformLive}

case class CustomServerCtx()
case class CustomClientCtx()

class TransportModelTest extends WordSpec {
  val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()


  "transport model" should {
    "support method calls" in {

      // all the server
      val server = new CalcServerImpl[IO, CustomServerCtx]
      val serverctxdec = new CtxDec[IO, ServerDispatcherError, Map[String, String], CustomServerCtx] {
        override def decode(c: Map[String, String]): IO[ServerDispatcherError, CustomServerCtx] = IO.succeed(CustomServerCtx())
      }
      val serverDispatcher: GeneratedCalcServerDispatcher[IO, CustomServerCtx, Map[String, String], Json] = new GeneratedCalcServerDispatcher[IO, CustomServerCtx, Map[String, String], Json](
        server,
        serverctxdec,
        codecs
      )

      // all the client
      val transport = new ClientTransport[IO, Map[String, String], Json] {
        override def dispatch(methodId: GeneratedServerBase.MethodId, body: Json): IO[ClientDispatcherError, GeneratedServerBase.ClientResponse[Map[String, String], Json]] = {
          for {
            out <- serverDispatcher.dispatch(methodId, ServerWireRequest(Map("client.ip" -> "1.2.3.4"), body)).catchAll(sde => IO.fail(ServerError(sde)))
          } yield {
            ClientResponse(Map(), out.value)
          }
        }
      }

      val clientctxdec = new CtxDec[IO, ClientDispatcherError, Map[String, String], CustomClientCtx] {
        override def decode(c: Map[String, String]): IO[ClientDispatcherError, CustomClientCtx] = IO.succeed(CustomClientCtx())
      }
      val client = new GeneratedCalcClientDispatcher[IO, CustomClientCtx, Map[String, String], Json](
        clientctxdec,
        codecs,
        transport,
        new ClientHook[IO, CustomClientCtx, Map[String, String], Json] {
          override def onDecode[A: IRTCodec[*, Json]](res: ClientResponse[Map[String, String], Json], c: CustomClientCtx, next: (CustomClientCtx, ClientResponse[Map[String, String], Json]) => IO[ClientDispatcherError, A]): IO[ClientDispatcherError, A] = {
            println(s"Client hook: ${res.value}")
            super.onDecode(res, c, next)
          }
        }
      )

      import zio._
      val runtime = new DefaultRuntime {
        override val Platform: Platform = PlatformLive.makeDefault().withReportFailure(_ => ())
      }
      println(runtime.unsafeRunSync(client.div(6, 2)))
      println(runtime.unsafeRunSync(client.div(6, 0)))
    }
  }
}
