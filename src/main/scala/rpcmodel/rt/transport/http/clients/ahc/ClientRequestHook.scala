package rpcmodel.rt.transport.http.clients.ahc

import scala.annotation.unchecked.uncheckedVariance

trait ClientRequestHook[-C, CTX[T], O] {
  def onRequest(c: CTX[C@uncheckedVariance], request: CTX[C@uncheckedVariance] => O): O
}

object ClientRequestHook {
  class Aux1[W, K[_] <: BaseClientContext[_]] {
    def passthrough[T]: ClientRequestHook[W, K, T] = {
      (c: K[W], request: K[W] => T) => request(c)
    }
  }

  def forCtx[W]: Aux1[W, SimpleRequestContext] = new Aux1[W, SimpleRequestContext]

  def forCtxEx[W, K[_] <: BaseClientContext[_]]: Aux1[W, K] = new Aux1[W, K]
}