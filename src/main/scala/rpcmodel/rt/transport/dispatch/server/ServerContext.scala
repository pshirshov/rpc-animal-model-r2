package rpcmodel.rt.transport.dispatch.server

import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{ServerWireRequest, ServerWireResponse}

trait ServerContext[F[_, _], C, WValue] {
  type Req = ServerWireRequest[C, WValue]
  type Res = ServerWireResponse[WValue]
}
