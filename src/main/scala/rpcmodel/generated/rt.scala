package rpcmodel.generated

import io.circe.{Decoder, Encoder, Json}
import izumi.functional.bio.{BIO, BIOMonadError, BIOPanic}
import rpcmodel.generated.ICalc.ZeroDivisionError
import rpcmodel.generated.ICalcServerWrappedImpl.{DivInput, DivOutput, SumInput, SumOutput}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.codecs.IRTCodec.IRTCodecFailure
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase._
import rpcmodel.rt.transport.dispatch._
import rpcmodel.rt.transport.dispatch.client.{ClientHook, ClientTransport, GeneratedClientBase}
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBaseImpl, ServerHook}
import rpcmodel.rt.transport.errors.ServerDispatcherError

trait GeneratedCalcCodecs[WValue] {
  type _IRTCodec1[T] = IRTCodec[T, WValue]

  implicit def codec_SumInput: _IRTCodec1[SumInput]

  implicit def codec_SumOutput: _IRTCodec1[SumOutput]

  implicit def codec_DivInput: _IRTCodec1[DivInput]

  implicit def codec_DivOutput: _IRTCodec1[DivOutput]

  implicit def codec_DivOutputError: _IRTCodec1[ZeroDivisionError]

  implicit def codec_Output[B: _IRTCodec1, G: _IRTCodec1]: _IRTCodec1[RPCResult[B, G]]
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



class GeneratedCalcServerDispatcher[F[+ _, + _] : BIOMonadError, C, WValue]
(
  server: ICalc.Interface[F, C],
  codecs: GeneratedCalcCodecs[WValue],
  override val hook: ServerHook[F, C, WValue],
) extends GeneratedServerBaseImpl[F, C, WValue] {

  import BIO._
  import codecs._


  private val sumId = MethodId(id, MethodName("sum"))
  private val divId = MethodId(id, MethodName("div"))

  override def id: ServiceName = ServiceName("CalcService")

  val methods: Map[MethodId, Req => F[ServerDispatcherError, Res]] = Map(sumId -> sum, divId -> div)

  private def sum(r: Req): F[ServerDispatcherError, Res] = {
    for {
      reqBody <- doDecode[SumInput](r)
      resBody <- server.sum(r.c, reqBody.a, reqBody.b).map(v => SumOutput(v))
      response <- doEncode(r, reqBody, resBody)
    } yield {
      response
    }
  }

  private def div(r: Req): F[ServerDispatcherError, Res] = {
    for {
      reqBody <- doDecode[DivInput](r)
      resBody <- server.div(r.c, reqBody.a, reqBody.b)
        .redeem[Nothing, RPCResult[ZeroDivisionError, DivOutput]](e => BIOMonadError[F].pure(RPCResult.Bad(e)), g => F.pure(RPCResult.Good(DivOutput(g))))
      response <- doEncode(r, reqBody, resBody)
    } yield {
      response
    }
  }
}


class GeneratedCalcClientDispatcher[F[+ _, + _] : BIOPanic, C, ResponseContext, WValue]
(
  codecs: GeneratedCalcCodecs[WValue],
  override val transport: ClientTransport[F, C, ResponseContext, WValue],
  override val hook: ClientHook[F, ResponseContext, WValue] = ClientHook.nothing[F, ResponseContext, WValue],
) extends GeneratedClientBase[F, C, ResponseContext, WValue] with ICalc.Interface[F, C] {

  import BIO._
  import codecs._

  override def sum(c: C, a: Int, b: Int): F[Nothing, Int] = {
    val id = MethodId(ServiceName("CalcService"), MethodName("sum"))
    val codec = implicitly[IRTCodec[SumInput, WValue]]

    val out = for {
      input <- F.pure(SumInput(a, b))
      encoded = codec.encode(input)
      dispatched <- transport.dispatch(c, id, encoded)
      decodedRes <- doDecode[SumOutput](dispatched)
    } yield {
      decodedRes.a
    }


    out.catchAll(e => F.terminate(ClientDispatcherException(e)))
  }


  override def div(c: C, a: Int, b: Int): F[ZeroDivisionError, Int] = {
    val id = MethodId(ServiceName("CalcService"), MethodName("div"))
    val codec = implicitly[IRTCodec[DivInput, WValue]]

    val out = for {
      input <- F.pure(DivInput(a, b))
      encoded = codec.encode(input)
      dispatched <- transport.dispatch(c, id, encoded)
      decodedRes <- doDecode[RPCResult[ZeroDivisionError, DivOutput]](dispatched)
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
  import io.circe.generic.semiauto._
  import io.circe.{Decoder, Encoder}

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
