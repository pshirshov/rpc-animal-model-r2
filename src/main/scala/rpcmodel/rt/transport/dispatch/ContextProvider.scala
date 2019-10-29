package rpcmodel.rt.transport.dispatch

import izumi.functional.bio.BIO
import BIO._

trait ContextProvider[F[+ _, + _], +E, -WC, C] {
  def decode(c: WC): F[E, C]
}

object ContextProvider {


  class Aux[F[+ _, + _] : BIO] {
    def const[C](value: C): ContextProvider[F, Nothing, Any, C] = {
      _: Any => F.pure(value)
    }

    def pure[W, C](f: W => C): ContextProvider[F, Nothing, W, C] = {
      w: W => F.pure(f(w))
    }

    def flat[W, C, E](f: W => F[E, C]): ContextProvider[F, E, W, C] = {
      w: W => f(w)
    }
  }

  def forF[F[+ _, + _] : BIO]: Aux[F] = new Aux[F]
}