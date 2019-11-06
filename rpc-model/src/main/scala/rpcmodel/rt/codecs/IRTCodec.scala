package rpcmodel.rt.codecs

import rpcmodel.rt.codecs.IRTCodec.IRTCodecFailure

trait IRTCodec[T, W] {
  def encode(justValue: T): W

  def decode(wireValue: W): Either[List[IRTCodecFailure], T]
}

object IRTCodec {

  sealed trait IRTCodecFailure

  object IRTCodecFailure {

    final case class IRTParserException(throwable: Throwable) extends IRTCodecFailure

    final case class IRTCodecException(throwable: Throwable) extends IRTCodecFailure

  }

}
