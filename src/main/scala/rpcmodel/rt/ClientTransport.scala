package rpcmodel.rt

import izumi.functional.bio.BIOError
import rpcmodel.rt.ServerDispatcher.{ClientDispatcherError, ClientResponse, CodecFailure1, MethodId}

trait ClientHook[F[_, _], C, WCtxIn, WValue] {
  def onCtxDecode(res: ClientResponse[WCtxIn, WValue], next: ClientResponse[WCtxIn, WValue] => F[ClientDispatcherError, C]): F[ClientDispatcherError, C] = {
    next(res)
  }

  def onDecode[E, A](res: ClientResponse[WCtxIn, WValue], c: C, next: (C, ClientResponse[WCtxIn, WValue]) => F[E, A]): F[E, A] = {
    next(c, res)
  }
}

object ClientHook {
  def nothing[F[_, _], C, WCtxIn, WValue]: ClientHook[F, C, WCtxIn, WValue] = new ClientHook[F, C, WCtxIn, WValue] {}
}


trait ClientTransport[F[_, _], WCtxIn, WValue] {
  def dispatch(methodId: MethodId, body: WValue): F[ClientDispatcherError, ClientResponse[WCtxIn, WValue]]
}

abstract class ClientTransportBaseImpl[F[+_, +_] : BIOError, C, WCtxIn, WValue] {
  import izumi.functional.bio.BIO._

  def hook: ClientHook[F, C, WCtxIn, WValue] = ClientHook.nothing

  protected final def doDecode[V : IRTCodec[*, WValue]](r: ClientResponse[WCtxIn, WValue], c: C): F[ClientDispatcherError, V] = {
    val codecRes = implicitly[IRTCodec[V, WValue]]

    hook.onDecode(r, c, (_, r) => F.fromEither(codecRes.decode(r.value).left.map(f => CodecFailure1(f))))

  }
}
