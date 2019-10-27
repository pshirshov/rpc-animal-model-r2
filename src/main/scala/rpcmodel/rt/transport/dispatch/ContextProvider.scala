package rpcmodel.rt.transport.dispatch

trait ContextProvider[F[_, _], E, WC, C] {
  def decode(c: WC): F[E, C]
}
