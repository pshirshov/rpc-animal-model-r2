package rpcmodel.rt.transport.dispatch.client

import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{ClientResponse, MethodId}
import rpcmodel.rt.transport.errors.ClientDispatcherError

trait ClientTransport[F[_, _], C, WCtxIn, WValue] {
  def connect(): F[ClientDispatcherError, Unit]
  def disconnect(): F[ClientDispatcherError, Unit]
  def dispatch(c: C, methodId: MethodId, body: WValue): F[ClientDispatcherError, ClientResponse[WCtxIn, WValue]]
}
