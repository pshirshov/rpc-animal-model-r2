package rpcmodel.rt.transport.http.clients.ahc

import io.circe.Json
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase

trait ClientRequestHook[-C, O] {
  def onRequest(c: C, methodId: GeneratedServerBase.MethodId, body: Json, request: => O): O
}

object ClientRequestHook {
  class Aux[W] {
    def passthrough[T]: ClientRequestHook[W, T] = {
      new ClientRequestHook[W, T] {
        override def onRequest(c: W, methodId: GeneratedServerBase.MethodId, body: Json, request: => T): T = {
          request
        }
      }
    }
  }

  def forCtx[W]: Aux[W] = new Aux[W]
}