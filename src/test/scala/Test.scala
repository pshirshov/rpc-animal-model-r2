import io.circe._
import org.scalatest.WordSpec
import rpcmodel.generated.ICalcServerWrappedImpl._
import rpcmodel.generated.{CalcClientDispatcher, CalcCodecs, CalcServerDispatcher, ICalc}
import rpcmodel.rt.IRTCodec.IRTCodecFailure
import rpcmodel.rt.ServerDispatcher.{ClientDispatcherError, ClientDispatcherException, ClientResponse, ServerDispatcherError, ServerError, ServerWireRequest}
import rpcmodel.rt.{ClientTransport, CtxDec, IRTCodec, ServerDispatcher}
import rpcmodel.user.impl.CalcServerImpl
import zio._
import zio.internal.{Platform, PlatformLive}

case class ServerCtx()
case class ClientCtx()

class TransportModelTest extends WordSpec {
  val codecs: CalcCodecs[Json] = new CalcCodecs[Json] {

    def e[T: Encoder](v: T): Json = implicitly[Encoder[T]].apply(v)
    def d[T: Decoder](v: Json): Either[List[IRTCodec.IRTCodecFailure], T] =implicitly[Decoder[T]].decodeJson(v).left.map(l => List(IRTCodecFailure.IRTCodecException(l)))

    override implicit def codec_SumInput: IRTCodec[SumInput, Json] = new IRTCodec[SumInput, Json] {
      override def encode(justValue: SumInput): Json = e(justValue)

      override def decode(wireValue: Json): Either[List[IRTCodec.IRTCodecFailure], SumInput] = d[SumInput](wireValue)
    }

    override implicit def codec_SumOutput: IRTCodec[SumOutput, Json] = new IRTCodec[SumOutput, Json] {
      override def encode(justValue: SumOutput): Json = e(justValue)

      override def decode(wireValue: Json): Either[List[IRTCodec.IRTCodecFailure], SumOutput] = d[SumOutput](wireValue)
    }

    override implicit def codec_DivInput: IRTCodec[DivInput, Json] = new IRTCodec[DivInput, Json] {
      override def encode(justValue: DivInput): Json = e(justValue)

      override def decode(wireValue: Json): Either[List[IRTCodec.IRTCodecFailure], DivInput] = d[DivInput](wireValue)
    }

    override implicit def codec_DivOutput: IRTCodec[DivOutput, Json] = new IRTCodec[DivOutput, Json] {
      override def encode(justValue: DivOutput): Json = e(justValue)

      override def decode(wireValue: Json): Either[List[IRTCodec.IRTCodecFailure], DivOutput] = d[DivOutput](wireValue)
    }

    override implicit def codec_DivOutputError: IRTCodec[ICalc.ZeroDivisionError, Json] = new IRTCodec[ICalc.ZeroDivisionError, Json] {
      override def encode(justValue: ICalc.ZeroDivisionError): Json = e(justValue)

      override def decode(wireValue: Json): Either[List[IRTCodec.IRTCodecFailure], ICalc.ZeroDivisionError] = d[ICalc.ZeroDivisionError](wireValue)
    }



    override implicit def codec_Output[B: Encoder : Decoder, G: Encoder : Decoder]: IRTCodec[ServerDispatcher.RPCResult[B, G], Json] = new IRTCodec[ServerDispatcher.RPCResult[B, G], Json] {
      override def encode(justValue: ServerDispatcher.RPCResult[B, G]): Json = e(justValue)

      override def decode(wireValue: Json): Either[List[IRTCodec.IRTCodecFailure], ServerDispatcher.RPCResult[B, G]] = d[ServerDispatcher.RPCResult[B, G]](wireValue)
    }

  }


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
        transport

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
