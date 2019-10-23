package rpcmodel.rt.transport.dispatch

import rpcmodel.rt.transport.dispatch.GeneratedServerBase.{ServerWireRequest, ServerWireResponse}

trait ServerContext[F[_, _], C, WCtxIn, WValue] {
  type Req = ServerWireRequest[WCtxIn, WValue]
  type Res = ServerWireResponse[WValue]
}
