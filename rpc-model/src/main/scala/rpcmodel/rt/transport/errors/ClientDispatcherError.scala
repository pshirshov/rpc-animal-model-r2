package rpcmodel.rt.transport.errors

import rpcmodel.rt.codecs.IRTCodec.IRTCodecFailure
import rpcmodel.rt.transport.dispatch.server.Envelopes.RemoteError
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.MethodId
import rpcmodel.rt.transport.dispatch.server.InvokationId


sealed trait ClientDispatcherError

object ClientDispatcherError {
  case class ServerError(err: RemoteError) extends ClientDispatcherError

  sealed trait LocalError extends ClientDispatcherError
  case class TimeoutException(id: InvokationId, methodId: MethodId) extends LocalError
  case class OperationUnsupported() extends LocalError
  case class ClientCodecFailure(failures: List[IRTCodecFailure]) extends LocalError
  case class UnknownException(t: Throwable) extends LocalError
  case class RestMappingError(e: List[MappingError]) extends LocalError
}