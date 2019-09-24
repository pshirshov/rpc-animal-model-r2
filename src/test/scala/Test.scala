import io.circe._
import org.scalatest.WordSpec
import rpcmodel.generated.{CalcClientDispatcher, CalcCodecs, CalcCodecsCirceJson, CalcServerDispatcher}
import rpcmodel.rt.ServerDispatcher._
import rpcmodel.rt.{ClientHook, ClientTransport, CtxDec, ServerDispatcher}
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.internal.{Platform, PlatformLive}

case class ServerCtx()
case class ClientCtx()

class TransportModelTest extends WordSpec {
  val codecs: CalcCodecs[Json] = new CalcCodecsCirceJson()


  "transport model" should {
    "support method calls" in {

      val server = new CalcServerImpl[IO, ServerCtx]
      val serverctxdec = new CtxDec[IO, ServerDispatcherError, Map[String, String], ServerCtx] {
        override def decode(c: Map[String, String]): IO[ServerDispatcherError, ServerCtx] = IO.succeed(ServerCtx())
      }


      val serverDispatcher = new CalcServerDispatcher[IO, ServerCtx, Map[String, String], Json](
        server,
        serverctxdec,
        codecs
      )

      val clientctxdec = new CtxDec[IO, ClientDispatcherError, Map[String, String], ClientCtx] {
        override def decode(c: Map[String, String]): IO[ClientDispatcherError, ClientCtx] = IO.succeed(ClientCtx())
      }

      val transport = new ClientTransport[IO, Map[String, String], Json] {
        override def dispatch(methodId: ServerDispatcher.MethodId, body: Json): IO[ClientDispatcherError, ServerDispatcher.ClientResponse[Map[String, String], Json]] = {
          for {
            out <- serverDispatcher.dispatch(methodId, ServerWireRequest(Map("client.ip" -> "1.2.3.4"), body)).catchAll(sde => IO.fail(ServerError(sde)))
          } yield {
            ClientResponse(Map(), out.value)
          }
        }
      }
      val client = new CalcClientDispatcher[IO, ClientCtx, Map[String, String], Json](
        clientctxdec,
        codecs,
        transport,
        new ClientHook[IO, ClientCtx, Map[String, String], Json] {
          override def onDecode[E, A](res: ClientResponse[Map[String, String], Json], c: ClientCtx, next: (ClientCtx, ClientResponse[Map[String, String], Json]) => IO[E, A]): IO[E, A] = {
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
