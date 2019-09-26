package rpcmodel.rt

import io.circe.{Json, Printer}
import rpcmodel.rt.IRTCodec.IRTCodecFailure

trait CirceConverter {
  def printer: Printer

  implicit def asString[A: IRTCodec[*, Json]]: IRTCodec[A, String] = {
    val jc = implicitly[IRTCodec[A, Json]]
    new IRTCodec[A, String] {
      override def encode(justValue: A): String = {
        jc.encode(justValue).printWith(printer)
      }

      override def decode(wireValue: String): Either[List[IRTCodecFailure], A] = {
        for {
          parsed <- io.circe.parser.parse(wireValue).left.map(f => List(IRTCodecFailure.IRTParserException(f)))
          decoded <- jc.decode(parsed)
        } yield {
          decoded
        }
      }
    }
  }
}
