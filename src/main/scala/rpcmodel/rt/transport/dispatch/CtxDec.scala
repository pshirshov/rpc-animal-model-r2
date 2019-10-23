package rpcmodel.rt.transport.dispatch

trait CtxDec[F[_, _], E, WC, C] {
  def decode(c: WC): F[E, C]
}
