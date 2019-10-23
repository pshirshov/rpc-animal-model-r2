package rpcmodel.rt.transport.dispatch

import rpcmodel.rt.transport.dispatch.GeneratedServerBase.{ServerWireRequest, ServerWireResponse}

trait ServerContext[F[_, _], C, WValue] {
  type Req = ServerWireRequest[C, WValue]
  type Res = ServerWireResponse[WValue]
}
