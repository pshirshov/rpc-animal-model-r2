package rpcmodel.rt.transport.errors

import rpcmodel.rt.transport.dispatch.GeneratedServerBase.MethodId

sealed trait ServerTransportError

object ServerTransportError {
  case class DomainError[V](value: V) extends ServerTransportError

  sealed trait Predefined extends ServerTransportError
  case class TransportException(e: Exception) extends Predefined
  case class DispatcherError(e: ServerDispatcherError) extends Predefined
  case class MethodIdError(path: String) extends Predefined
  case class MissingMethod(id: MethodId) extends Predefined
  case class JsonCodecError(s: String, e: Throwable) extends Predefined
  case class EnvelopeFormatError(s: String, e: Throwable) extends Predefined
}