package rpcmodel.rt.transport.dispatch

import rpcmodel.rt.transport.dispatch.GeneratedServerBase.{ClientResponse, MethodId}
import rpcmodel.rt.transport.errors.ClientDispatcherError


trait ClientTransport[F[_, _], C, WValue] {
  def dispatch(methodId: MethodId, body: WValue): F[ClientDispatcherError, ClientResponse[C, WValue]]
}


