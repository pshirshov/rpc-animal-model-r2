package rpcmodel.rt.transport.dispatch.client

import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError

trait ClientHook[F[_, _], C, WValue] {
  def onCtxDecode(res: ClientResponse[C, WValue], next: ClientResponse[C, WValue] => F[ClientDispatcherError, C]): F[ClientDispatcherError, C] = {
    next(res)
  }

  def onDecode[A : IRTCodec[*, WValue]](res: ClientResponse[C, WValue], next: ClientResponse[C, WValue] => F[ClientDispatcherError, A]): F[ClientDispatcherError, A] = {
    next(res)
  }
}

object ClientHook {
  def nothing[F[_, _], C, WValue]: ClientHook[F, C, WValue] = new ClientHook[F, C, WValue] {}
}