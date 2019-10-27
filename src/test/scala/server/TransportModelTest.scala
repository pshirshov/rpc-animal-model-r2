package server

import io.circe._
import org.scalatest.WordSpec
import rpcmodel.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase._
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.errors.ClientDispatcherError.ServerError
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.internal.{Platform, PlatformLive}

case class C2SRequestServerCtx(ip: String, headers: Map[String, Seq[String]])

case class S2CReqestClientCtx()
case class S2CResponseClientCtx()

case class C2SReqestClientCtx()
case class C2SResponseClientCtx()

case class CustomWsMeta(history: List[String])

class TransportModelTest extends WordSpec {
  private val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()


  "transport model" should {
    "support method calls" in {
      val server = new CalcServerImpl[IO, C2SRequestServerCtx]
      val serverDispatcher: GeneratedCalcServerDispatcher[IO, C2SRequestServerCtx, Json] = new GeneratedCalcServerDispatcher[IO, C2SRequestServerCtx, Json](
        server,
        codecs,
      )

      val fakeTransport = new ClientTransport[IO, C2SResponseClientCtx, C2SResponseClientCtx, Json] {
        override def connect(): IO[ClientDispatcherError, Unit] = IO.unit

        override def disconnect(): IO[ClientDispatcherError, Unit] = IO.fail(ClientDispatcherError.OperationUnsupported())

        override def dispatch(c: C2SResponseClientCtx, methodId: GeneratedServerBase.MethodId, body: Json): IO[ClientDispatcherError, GeneratedServerBase.ClientResponse[C2SResponseClientCtx, Json]] = {
          for {
            out <- serverDispatcher.dispatch(methodId, ServerWireRequest(C2SRequestServerCtx("0.1.2.3", Map("header" -> Seq("value"))), body)).catchAll(sde => IO.fail(ServerError(sde)))
          } yield {
            ClientResponse(C2SResponseClientCtx(), out.value)
          }
        }
      }


      val client = new GeneratedCalcClientDispatcher[IO, C2SResponseClientCtx, C2SResponseClientCtx, Json](
        codecs,
        fakeTransport,
      )

      import zio._
      val runtime = new DefaultRuntime {
        override val Platform: Platform = PlatformLive.makeDefault().withReportFailure(_ => ())
      }
      println(runtime.unsafeRunSync(client.div(C2SResponseClientCtx(), 6, 2)))
      println(runtime.unsafeRunSync(client.div(C2SResponseClientCtx(), 6, 0)))
    }
  }


}
