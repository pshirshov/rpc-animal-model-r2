package rpcmodel.rt.transport.http.clients.ahc

import rpcmodel.rt.transport.errors.ClientDispatcherError

trait ClientRequestHook[W, O] {
  def onRequest(c: W, request: W => O): Either[ClientDispatcherError, O]
}

object ClientRequestHook {
  def passthrough[W, T]: ClientRequestHook[W, T] = forCtx[W].passthrough[T]

  def forCtx[W]: ForCtx2PartiallyApplied[W] = new ForCtx2PartiallyApplied[W]

  final class ForCtx2PartiallyApplied[W](private val dummy: Boolean = false) extends AnyVal {
    def passthrough[T]: ClientRequestHook[W, T] = {
      (c: W, request: W => T) => Right(request(c))
    }
  }
}
