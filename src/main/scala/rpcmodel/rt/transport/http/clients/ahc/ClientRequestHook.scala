package rpcmodel.rt.transport.http.clients.ahc

import io.circe.Json
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase

trait ClientRequestHook[-C, O] {
  def onRequest(c: C, methodId: GeneratedServerBase.MethodId, body: Json, request: => O): O
}

object ClientRequestHook {
  def passthrough[T]: ClientRequestHook[Any, T] = new ClientRequestHook[Any, T] {
    override def onRequest(c: Any, methodId: GeneratedServerBase.MethodId, body: Json, request: => T): T = {
      request
    }
  }
}