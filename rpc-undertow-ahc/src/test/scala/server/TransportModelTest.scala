package server

import io.circe._
import org.scalatest.WordSpec
import server.fixtures.generated.{GeneratedCalcClientDispatcher, GeneratedCalcCodecs, GeneratedCalcCodecsCirceJson, GeneratedCalcServerDispatcher}
import rpcmodel.rt.transport.dispatch.client.ClientTransport
import rpcmodel.rt.transport.dispatch.server.Envelopes.RemoteError
import rpcmodel.rt.transport.dispatch.server.Envelopes.RemoteError.ShortException
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase._
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.errors.ClientDispatcherError.ServerError
import server.fixtures.user.impl.CalcServerImpl
import zio._
import zio.internal.{Platform, PlatformLive}

final case class IncomingServerCtx(ip: String, headers: Map[String, Seq[String]])
final case class C2SOutgoingCtx()
final case class OutgoingPushServerCtx()

final case class CustomWsMeta(history: List[String])

class TransportModelTest extends WordSpec {
  private val codecs: GeneratedCalcCodecs[Json] = new GeneratedCalcCodecsCirceJson()

  "transport model" should {
    "support method calls" in {
      val server = new CalcServerImpl[IO, IncomingServerCtx]
      val serverDispatcher = new GeneratedCalcServerDispatcher(server, codecs)

      val fakeTransport = new ClientTransport[IO, C2SOutgoingCtx, Json] {
        override def connect(): IO[ClientDispatcherError, Unit] = IO.unit

        override def disconnect(): IO[ClientDispatcherError, Unit] = IO.fail(ClientDispatcherError.OperationUnsupported())

        override def dispatch(c: C2SOutgoingCtx, methodId: GeneratedServerBase.MethodId, body: Json): IO[ClientDispatcherError, GeneratedServerBase.ClientResponse[Json]] = {
          for {
            out <- serverDispatcher.dispatch(methodId, ServerWireRequest(IncomingServerCtx("0.1.2.3", Map("header" -> Seq("value"))), body))
              .catchAll(sde => IO.fail(ServerError(RemoteError.Critical(Seq(ShortException("???", s"something is wrong: $sde"))))))
          } yield {
            ClientResponse(out.value)
          }
        }
      }

      val client = new GeneratedCalcClientDispatcher[IO, C2SOutgoingCtx, Json](
        codecs,
        fakeTransport,
      )

      import zio._
      val runtime = new DefaultRuntime {
        override val Platform: Platform = PlatformLive.makeDefault().withReportFailure(_ => ())
      }
      println(runtime.unsafeRunSync(client.div(C2SOutgoingCtx(), 6, 2)))
      println(runtime.unsafeRunSync(client.div(C2SOutgoingCtx(), 6, 0)))
    }
  }

}
