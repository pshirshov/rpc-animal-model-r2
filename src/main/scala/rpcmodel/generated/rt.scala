package rpcmodel.generated

import io.circe.{Decoder, Encoder}
import izumi.functional.bio.{BIO, BIOMonadError, BIOPanic}
import rpcmodel.generated.ICalc.ZeroDivisionError
import rpcmodel.generated.ICalcServerWrappedImpl.{DivInput, DivOutput, SumInput, SumOutput}
import rpcmodel.rt.ServerDispatcher.{ServerDispatcherError, _}
import rpcmodel.rt._

trait CalcCodecs[WValue] {
  implicit def codec_SumInput: IRTCodec[SumInput, WValue]

  implicit def codec_SumOutput: IRTCodec[SumOutput, WValue]

  implicit def codec_DivInput: IRTCodec[DivInput, WValue]

  implicit def codec_DivOutput: IRTCodec[DivOutput, WValue]

  implicit def codec_DivOutputError: IRTCodec[ZeroDivisionError, WValue]

  //implicit def codec_Output[B: IRTCodec[*, WValue], G: IRTCodec[*, WValue]]: IRTCodec[RPCResult[B, G], WValue]
  implicit def codec_Output[B: Encoder : Decoder, G: Encoder : Decoder]: IRTCodec[RPCResult[B, G], WValue]
}

class CalcServerDispatcher[F[+ _, + _] : BIOMonadError, C, WCtxIn, WValue]
(
  server: ICalc.Server[F, C],
  ctxdec: CtxDec[F, ServerDispatcherError, WCtxIn, C],
  codecs: CalcCodecs[WValue],
  override val hook: ServerHook[F, C, WCtxIn, WValue] = ServerHook.nothing[F, C, WCtxIn, WValue],
) extends DispatherBaseImpl[F, C, WCtxIn, WValue] {

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


class CalcClientDispatcher[F[+ _, + _] : BIOPanic, C, WCtxIn, WValue]
(
  ctxdec: CtxDec[F, ClientDispatcherError, WCtxIn, C],
  codecs: CalcCodecs[WValue],
  transport: ClientTransport[F, WCtxIn, WValue],
  override val hook: ClientHook[F, C, WCtxIn, WValue] = ClientHook.nothing[F, C, WCtxIn, WValue],
) extends ClientTransportBaseImpl[F, C, WCtxIn, WValue] with ICalc.Client[F] {

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
