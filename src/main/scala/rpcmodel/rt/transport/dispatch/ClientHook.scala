package rpcmodel.rt.transport.dispatch

import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError

trait ClientHook[F[_, _], C, WCtxIn, WValue] {
  def onCtxDecode(res: ClientResponse[WCtxIn, WValue], next: ClientResponse[WCtxIn, WValue] => F[ClientDispatcherError, C]): F[ClientDispatcherError, C] = {
    next(res)
  }

  def onDecode[A : IRTCodec[*, WValue]](res: ClientResponse[WCtxIn, WValue], c: C, next: (C, ClientResponse[WCtxIn, WValue]) => F[ClientDispatcherError, A]): F[ClientDispatcherError, A] = {
    next(c, res)
  }
}

object ClientHook {
  def nothing[F[_, _], C, WCtxIn, WValue]: ClientHook[F, C, WCtxIn, WValue] = new ClientHook[F, C, WCtxIn, WValue] {}
}