package rpcmodel.rt.transport.errors

import rpcmodel.rt.transport.codecs.IRTCodec.IRTCodecFailure


sealed trait ClientDispatcherError

object ClientDispatcherError {

  case class UnknownException(t: Throwable) extends ClientDispatcherError

  case class ServerError(s: ServerDispatcherError) extends ClientDispatcherError

  case class ClientCodecFailure(failures: List[IRTCodecFailure]) extends ClientDispatcherError

}