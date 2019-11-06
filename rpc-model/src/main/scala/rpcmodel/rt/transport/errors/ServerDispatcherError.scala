package rpcmodel.rt.transport.errors

import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.MethodId
import rpcmodel.rt.codecs.IRTCodec.IRTCodecFailure

sealed trait ServerDispatcherError

object ServerDispatcherError {
  case class MethodHandlerMissing(methodId: MethodId) extends ServerDispatcherError
  case class ServerCodecFailure(failures: List[IRTCodecFailure]) extends ServerDispatcherError
}