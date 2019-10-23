package server

import io.circe._
import org.scalatest.WordSpec
import rpcmodel.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.GeneratedServerBase._
import rpcmodel.rt.transport.dispatch.{ClientHook, ClientTransport, GeneratedServerBase}
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.errors.ClientDispatcherError.ServerError
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.internal.{Platform, PlatformLive}

case class CustomServerCtx(ip: String, headers: Map[String, Seq[String]])
case class CustomClientCtx()

class TransportModelTest extends WordSpec {
  val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()


  "transport model" should {
    "support method calls" in {
      val server = new CalcServerImpl[IO, CustomServerCtx]
      val serverDispatcher: GeneratedCalcServerDispatcher[IO, CustomServerCtx, Json] = new GeneratedCalcServerDispatcher[IO, CustomServerCtx, Json](
        server,
        codecs
      )

      val fakeTransport = new ClientTransport[IO, CustomClientCtx, Json] {
        override def dispatch(methodId: GeneratedServerBase.MethodId, body: Json): IO[ClientDispatcherError, GeneratedServerBase.ClientResponse[CustomClientCtx, Json]] = {
          for {
            out <- serverDispatcher.dispatch(methodId, ServerWireRequest(CustomServerCtx("0.1.2.3", Map("header" -> Seq("value"))), body)).catchAll(sde => IO.fail(ServerError(sde)))
          } yield {
            ClientResponse(CustomClientCtx(), out.value)
          }
        }
      }


      val client = new GeneratedCalcClientDispatcher[IO, CustomClientCtx, Json](
        codecs,
        fakeTransport,
        new ClientHook[IO, CustomClientCtx, Json] {
          override def onDecode[A: IRTCodec[*, Json]](res: ClientResponse[CustomClientCtx, Json], next: ClientResponse[CustomClientCtx, Json] => IO[ClientDispatcherError, A]): IO[ClientDispatcherError, A] = {
            println(s"Client hook: ${res.value}")
            super.onDecode(res, next)
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
