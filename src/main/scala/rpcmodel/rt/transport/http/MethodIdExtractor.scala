package rpcmodel.rt.transport.http

import rpcmodel.rt.transport.dispatch.GeneratedServerBase.{MethodId, MethodName, ServiceName}
import rpcmodel.rt.transport.errors.ServerTransportError

trait MethodIdExtractor {
  def extract(requestPath: String): Either[ServerTransportError, MethodId]
}

object MethodIdExtractor {
  class TailImpl() extends MethodIdExtractor {
    def extract(requestPath: String): Either[ServerTransportError, MethodId] = {
      val segments = requestPath.split('/').takeRight(2)
      Right(MethodId(ServiceName(segments.head), MethodName(segments.last)))
    }

  }

}