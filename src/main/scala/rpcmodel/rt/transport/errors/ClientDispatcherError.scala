package rpcmodel.rt.transport.errors

import io.circe.Json
import rpcmodel.rt.transport.codecs.IRTCodec.IRTCodecFailure
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.MethodId
import rpcmodel.rt.transport.http.servers.shared.InvokationId


sealed trait ClientDispatcherError

object ClientDispatcherError {

  case class TimeoutException(id: InvokationId, methodId: MethodId) extends ClientDispatcherError
  case class UnknownException(t: Throwable) extends ClientDispatcherError

  case class ServerError(/*s: ServerDispatcherError*/ err: Json) extends ClientDispatcherError // TODO: decode exceptions
  case class OperationUnsupported() extends ClientDispatcherError

  case class ClientCodecFailure(failures: List[IRTCodecFailure]) extends ClientDispatcherError

}