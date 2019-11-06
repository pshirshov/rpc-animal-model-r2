package rpcmodel.rt.transport.dispatch.server

import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{ServerWireRequest, ServerWireResponse}

trait ServerContext[+F[_, _], -C, WValue] {
  protected[this] type Req = ServerWireRequest[C, WValue]
  protected[this] type Res = ServerWireResponse[WValue]
}
