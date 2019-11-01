package rpcmodel.rt.transport.http.clients.ahc

trait ClientRequestHook[K[_], W, O] {
  def onRequest(c: K[W], request: K[W] => O): O
}

object ClientRequestHook {
  def passthrough[W, K[_], T]: ClientRequestHook[K, W, T] = forCtx[W, K].passthrough[T]

  def forCtx[W, K[_]]: ForCtxPartiallyApplied[W, K] = new ForCtxPartiallyApplied[W, K]
  def forCtx[W]: ForCtx2PartiallyApplied[W] = new ForCtx2PartiallyApplied[W]

  final class ForCtxPartiallyApplied[W, K[_]](private val dummy: Boolean = false) extends AnyVal {
    def passthrough[T]: ClientRequestHook[K, W, T] = {
      (c: K[W], request: K[W] => T) => request(c)
    }
  }

  final class ForCtx2PartiallyApplied[W](private val dummy: Int = 0) extends AnyVal {
    def passthrough[K[_], T]: ClientRequestHook[K, W, T] = {
      (c: K[W], request: K[W] => T) => request(c)
    }
  }
}
