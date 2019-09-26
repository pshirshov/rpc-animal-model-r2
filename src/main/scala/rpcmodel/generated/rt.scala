package rpcmodel.generated

import io.circe.{Decoder, Encoder, Json, Printer}
import izumi.functional.bio.{BIO, BIOMonadError, BIOPanic}
import rpcmodel.generated.ICalc.ZeroDivisionError
import rpcmodel.generated.ICalcServerWrappedImpl.{DivInput, DivOutput, SumInput, SumOutput}
import rpcmodel.rt.IRTCodec.IRTCodecFailure
import rpcmodel.rt.GeneratedServerBase.{ServerDispatcherError, _}
import rpcmodel.rt._

trait GeneratedCalcCodecs[WValue] {
  implicit def codec_SumInput: IRTCodec[SumInput, WValue]

  implicit def codec_SumOutput: IRTCodec[SumOutput, WValue]

  implicit def codec_DivInput: IRTCodec[DivInput, WValue]

  implicit def codec_DivOutput: IRTCodec[DivOutput, WValue]

  implicit def codec_DivOutputError: IRTCodec[ZeroDivisionError, WValue]

  implicit def codec_Output[B: IRTCodec[*, WValue], G: IRTCodec[*, WValue]]: IRTCodec[RPCResult[B, G], WValue]
}

trait GeneratedCalcCodecsCirce extends GeneratedCalcCodecs[Json] {
}

class GeneratedCalcCodecsCirceJson extends GeneratedCalcCodecsCirce {
  import io.circe.literal._

  def e[T: Encoder](v: T): Json = implicitly[Encoder[T]].apply(v)
  def d[T: Decoder](v: Json): Either[List[IRTCodec.IRTCodecFailure], T] = implicitly[Decoder[T]].decodeJson(v).left.map(l => List(IRTCodecFailure.IRTCodecException(l)))

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


  override implicit def codec_Output[B: IRTCodec[*, Json], G: IRTCodec[*, Json]]: IRTCodec[RPCResult[B, G], Json] = new IRTCodec[RPCResult[B, G], Json] {
    override def encode(justValue: RPCResult[B, G]): Json = {
      justValue match {
        case RPCResult.Good(value) => json"""{"s": ${implicitly[IRTCodec[G, Json]].encode(value)}}"""

        case RPCResult.Bad(value) =>json"""{"f": ${implicitly[IRTCodec[B, Json]].encode(value)}}"""
      }
    }

    override def decode(wireValue: Json): Either[List[IRTCodecFailure], RPCResult[B, G]] = {
      wireValue.asObject match {
        case Some(value) =>
          value.toMap.get("s") match {
            case Some(value) =>
              implicitly[IRTCodec[G, Json]].decode(value).map(v => RPCResult.Good(v))
            case None =>
              value.toMap.get("f") match {
                case Some(value) =>
                  implicitly[IRTCodec[B, Json]].decode(value).map(v => RPCResult.Bad(v))
                case None =>
                  Left(List(IRTCodecFailure.IRTCodecException(new RuntimeException(s"unexpected json: $wireValue"))))
              }
          }
        case None =>
          Left(List(IRTCodecFailure.IRTCodecException(new RuntimeException(s"unexpected json: $wireValue"))))
      }
    }
  }
}

class GeneratedCalcServerDispatcher[F[+ _, + _] : BIOMonadError, C, WCtxIn, WValue]
(
  server: ICalc.Server[F, C],
  ctxdec: CtxDec[F, ServerDispatcherError, WCtxIn, C],
  codecs: GeneratedCalcCodecs[WValue],
  override val hook: ServerHook[F, C, WCtxIn, WValue] = ServerHook.nothing[F, C, WCtxIn, WValue],
) extends GeneratedServerBaseImpl[F, C, WCtxIn, WValue] {

  import BIO._
  import codecs._

  private val sumId = MethodId(ServiceName("CalcService"), MethodName("sum"))
  private val divId = MethodId(ServiceName("CalcService"), MethodName("div"))
  val methods: Map[MethodId, Req => F[ServerDispatcherError, Res]] = Map(sumId -> sum, divId -> div)

  private def sum(r: Req): F[ServerDispatcherError, Res] = {
    for {
      ctx <- hook.onCtxDecode(r, req => ctxdec.decode(req.c))
      reqBody <- doDecode[SumInput](r, ctx)
      resBody <- server.sum(ctx, reqBody.a, reqBody.b).map(v => SumOutput(v))
      response <- doEncode(r, ctx, reqBody, resBody)
    } yield {
      response
    }
  }

  private def div(r: Req): F[ServerDispatcherError, Res] = {
    for {
      ctx <- hook.onCtxDecode(r, req => ctxdec.decode(req.c))
      reqBody <- doDecode[DivInput](r, ctx)
      resBody <- server.div(ctx, reqBody.a, reqBody.b)
        .redeem[Nothing, RPCResult[ZeroDivisionError, DivOutput]](e => F.pure(RPCResult.Bad(e)), g => F.pure(RPCResult.Good(DivOutput(g))))
      response <- doEncode(r, ctx, reqBody, resBody)
    } yield {
      response
    }
  }
}


class GeneratedCalcClientDispatcher[F[+ _, + _] : BIOPanic, C, WCtxIn, WValue]
(
  ctxdec: CtxDec[F, ClientDispatcherError, WCtxIn, C],
  codecs: GeneratedCalcCodecs[WValue],
  transport: ClientTransport[F, WCtxIn, WValue],
  override val hook: ClientHook[F, C, WCtxIn, WValue] = ClientHook.nothing[F, C, WCtxIn, WValue],
) extends GeneratedClientBase[F, C, WCtxIn, WValue] with ICalc.Client[F] {

  import codecs._
  import BIO._

  override def sum(a: Int, b: Int): F[Nothing, Int] = {
    val id = MethodId(ServiceName("CalcService"), MethodName("sum"))
    val codec = implicitly[IRTCodec[SumInput, WValue]]

    val out = for {
      input <- F.pure(SumInput(a, b))
      encoded = codec.encode(input)
      dispatched <- transport.dispatch(id, encoded)
      decodedC <- ctxdec.decode(dispatched.c)
      decodedRes <- doDecode[SumOutput](dispatched, decodedC)
    } yield {
      decodedRes.a
    }

    out.catchAll(e => F.terminate(ClientDispatcherException(e)))
  }


  override def div(a: Int, b: Int): F[ZeroDivisionError, Int] = {
    val id = MethodId(ServiceName("CalcService"), MethodName("div"))
    val codec = implicitly[IRTCodec[DivInput, WValue]]

    val out = for {
      input <- F.pure(DivInput(a, b))
      encoded = codec.encode(input)
      dispatched <- transport.dispatch(id, encoded)
      decodedC <- hook.onCtxDecode(dispatched, d => ctxdec.decode(d.c))
      decodedRes <- doDecode[RPCResult[ZeroDivisionError, DivOutput]](dispatched, decodedC)
    } yield {
      decodedRes
    }

    for {
      res <- out.catchAll(e => F.terminate(ClientDispatcherException(e)))
      out <- F.fromEither(res.toEither)
    } yield {
      out.a
    }
  }
}

object ICalcServerWrappedImpl {
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto._

  case class SumInput(a: Int, b: Int)
  object SumInput {
    implicit def e: Encoder[SumInput] = deriveEncoder
    implicit def d: Decoder[SumInput] = deriveDecoder
  }

  case class SumOutput(a: Int)
  object SumOutput {
    implicit def e: Encoder[SumOutput] = deriveEncoder
    implicit def d: Decoder[SumOutput] = deriveDecoder
  }

  case class DivInput(a: Int, b: Int)
  object DivInput {
    implicit def e: Encoder[DivInput] = deriveEncoder
    implicit def d: Decoder[DivInput] = deriveDecoder
  }

  case class DivOutput(a: Int)
  object DivOutput {
    implicit def e: Encoder[DivOutput] = deriveEncoder
    implicit def d: Decoder[DivOutput] = deriveDecoder
  }
}
