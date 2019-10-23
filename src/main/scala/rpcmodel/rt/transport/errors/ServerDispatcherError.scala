package rpcmodel.rt.transport.errors

import rpcmodel.rt.transport.dispatch.GeneratedServerBase.MethodId
import rpcmodel.rt.transport.codecs.IRTCodec.IRTCodecFailure

sealed trait ServerDispatcherError

object ServerDispatcherError {
  case class MethodHandlerMissing(methodId: MethodId) extends ServerDispatcherError
  case class ServerCodecFailure(failures: List[IRTCodecFailure]) extends ServerDispatcherError
}