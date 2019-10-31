package rpcmodel.rt.transport.http.clients.ahc

trait ClientRequestHook[C, CTX[_], O] {
  def onRequest(c: CTX[C], request: CTX[C] => O): O
}

object ClientRequestHook {
  class Aux[W, K[_] <: BaseClientContext[_]] {
    def passthrough[T]: ClientRequestHook[W, K, T] = {
      (c: K[W], request: K[W] => T) => request(c)
    }
  }

  def forCtx[W]: Aux[W, SimpleRequestContext] = new Aux[W, SimpleRequestContext]

  def forCtxEx[W, K[_] <: BaseClientContext[_]]: Aux[W, K] = new Aux[W, K]
}