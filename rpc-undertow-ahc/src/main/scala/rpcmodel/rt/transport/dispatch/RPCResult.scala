package rpcmodel.rt.transport.dispatch

import io.circe.{DecodingFailure, HCursor, Json}
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ResponseKind

sealed trait RPCResult[+B, +G] {
  def kind: GeneratedServerBase.ResponseKind

  def toEither: Either[B, G]
}

object RPCResult {

  import io.circe.syntax._
  import io.circe.{Decoder, Encoder}

  final val left = "left"
  final val right = "right"

  def wireLeft(json: Json): Json = Json.obj(left -> json)
  def wireRight(json: Json): Json = Json.obj(right -> json)

  implicit def eitherEncoder[L: Encoder, R: Encoder]: Encoder[Either[L, R]] = {
    case Left(value) =>
      wireLeft(value.asJson)

    case Right(value) =>
      wireRight(value.asJson)
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

  case class Good[+G](value: G) extends RPCResult[Nothing, G] {
    override def kind: ResponseKind = ResponseKind.RpcSuccess
    override def toEither: Either[Nothing, G] = Right(value)
  }

  case class Bad[+B](value: B) extends RPCResult[B, Nothing] {
    override def kind: ResponseKind = ResponseKind.RpcFailure
    override def toEither: Either[B, Nothing] = Left(value)
  }

}
