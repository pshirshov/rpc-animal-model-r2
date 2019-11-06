package rpcmodel.rt.transport.http.servers.shared

import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{MethodId, MethodName, ServiceName}
import rpcmodel.rt.transport.errors.ServerTransportError

trait MethodIdExtractor {
  def extract(requestPath: String): Either[ServerTransportError, MethodId]
}

object MethodIdExtractor {
  object TailImpl extends MethodIdExtractor {
    def extract(requestPath: String): Either[ServerTransportError, MethodId] = {
      val segments = requestPath.split('/').takeRight(2)
      Right(MethodId(ServiceName(segments.head), MethodName(segments.last)))
    }
  }
}