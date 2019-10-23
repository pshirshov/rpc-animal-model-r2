package rpcmodel.rt.transport.dispatch

import io.circe.{DecodingFailure, HCursor, Json}
import izumi.functional.bio.{BIO, BIOError}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.GeneratedServerBase._
import rpcmodel.rt.transport.errors.ServerDispatcherError.{MethodHandlerMissing, ServerCodecFailure}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerDispatcherError}


trait GeneratedServerBase[F[_, _], C, WCtxIn, WValue] extends ServerContext[F, C, WCtxIn, WValue] {
  def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]]
  def methods: Map[MethodId, Req => F[ServerDispatcherError, Res]]
  def id: ServiceName
}

abstract class GeneratedServerBaseImpl[F[+ _, + _] : BIOError, C, WCtxIn, WValue]
(

) extends GeneratedServerBase[F, C, WCtxIn, WValue] {

  import BIO._

  def hook: ServerHook[F, C, WCtxIn, WValue]

  override final def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    methods.get(methodId) match {
      case Some(value) =>
        value(r)
      case None =>
        F.fail(MethodHandlerMissing(methodId))
    }
  }

  protected final def doDecode[V: IRTCodec[*, WValue]](r: Req, c: C): F[ServerDispatcherError, V] = {
    val codec = implicitly[IRTCodec[V, WValue]]
    hook.onDecode(r, c, (req, _) => F.fromEither(codec.decode(req.value).left.map(f => ServerCodecFailure(f))))
  }

  protected final def doEncode[ResBody: IRTCodec[*, WValue], ReqBody: IRTCodec[*, WValue]](r: Req, c: C, reqBody: ReqBody, resBody: ResBody): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    val codec = implicitly[IRTCodec[ResBody, WValue]]
    for {
      out <- hook.onEncode(r, c, reqBody, resBody, (_: Req, _: C, _: ReqBody, rb: ResBody) => F.pure(codec.encode(rb)))
    } yield {
      ServerWireResponse(out)
    }
  }
}


object GeneratedServerBase {

  case class ClientResponse[WCtxIn, WValue](c: WCtxIn, value: WValue)

  case class ServerWireRequest[WCtxIn, WValue](c: WCtxIn, value: WValue)

  case class ServerWireResponse[WValue](value: WValue)

  case class ClientDispatcherException(error: ClientDispatcherError) extends RuntimeException

  sealed trait RPCResult[+B, +G] {
    def toEither: Either[B, G]
  }

  object RPCResult {

    import io.circe.syntax._
    import io.circe.{Decoder, Encoder}

    val left = "left"
    val right = "right"

    implicit def eitherEncoder[L: Encoder, R: Encoder]: Encoder[Either[L, R]] = {
      case Left(value) =>
        Json.obj(left -> value.asJson)

      case Right(value) =>
        Json.obj(right -> value.asJson)
    }

    implicit def eitherDecoder[L: Decoder, R: Decoder]: Decoder[Either[L, R]] = (c: HCursor) => {
      c.value.asObject match {
        case Some(value) =>
          if (value.contains(right)) {
            for {
              decoded <- c.downField(right).as[R]
            } yield {
              Right(decoded)
            }
          } else if (value.contains(left)) {
            for {
              decoded <- c.downField(left).as[L]
            } yield {
              Left(decoded)
            }
          } else {
            Left(DecodingFailure.apply(s"`$left` or `$right` expected", c.history))
          }
        case None =>
          Left(DecodingFailure.apply(s"Object expected", c.history))
      }
    }

    implicit def e[B: Encoder, G: Encoder]: Encoder[RPCResult[B, G]] = implicitly[Encoder[Either[B, G]]].contramap {
      case Good(value) =>
        Right(value)
      case Bad(value) =>
        Left(value)
    }

    implicit def d[B: Decoder, G: Decoder]: Decoder[RPCResult[B, G]] = implicitly[Decoder[Either[B, G]]].map {
      case Left(value) =>
        Bad(value)
      case Right(value) =>
        Good(value)
    }

    case class Good[+G](value: G) extends RPCResult[Nothing, G] {
      override def toEither: Either[Nothing, G] = Right(value)
    }

    case class Bad[+B](value: B) extends RPCResult[B, Nothing] {
      override def toEither: Either[B, Nothing] = Left(value)
    }

  }

  case class MethodName(name: String) extends AnyVal

  case class ServiceName(name: String) extends AnyVal

  case class MethodId(service: ServiceName, method: MethodName)

}


