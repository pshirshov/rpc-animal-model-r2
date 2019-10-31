package rpcmodel.rt.transport.http.clients.ahc

trait ClientRequestHook[F[_], C, O] {
  def onRequest(c: F[C], request: F[C] => O): O
}

object ClientRequestHook {
//  type Simple[C, O] = ClientRequestHook[SimpleRequestContext, C, O]

  class Aux[W, K[_] <: BaseClientContext[_]] {
    def passthrough[T]: ClientRequestHook[K, W, T] = {
      (c: K[W], request: K[W] => T) => request(c)
    }
  }

//  def forCtx[W]: Aux[W, SimpleRequestContext] = new Aux[W, SimpleRequestContext]

  def forCtx[W, K[_] <: BaseClientContext[_]]: Aux[W, K] = new Aux[W, K]
}