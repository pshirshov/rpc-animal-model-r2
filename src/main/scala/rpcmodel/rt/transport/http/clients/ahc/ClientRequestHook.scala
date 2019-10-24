package rpcmodel.rt.transport.http.clients.ahc

import io.circe.Json
import org.asynchttpclient.BoundRequestBuilder
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase

trait ClientRequestHook[-C] {
  def onRequest(c: C, methodId: GeneratedServerBase.MethodId, body: Json, request: => BoundRequestBuilder): BoundRequestBuilder
}

object ClientRequestHook {
  object Passthrough extends ClientRequestHook[Any] {
    override def onRequest(c: Any, methodId: GeneratedServerBase.MethodId, body: Json, request: => BoundRequestBuilder): BoundRequestBuilder = {
      request
    }
  }
}