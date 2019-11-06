package rpcmodel.rt.transport.dispatch.server

import rpcmodel.rt.transport.dispatch.server.Envelopes.RemoteError
import rpcmodel.rt.transport.errors.ServerTransportError

trait TransportErrorHandler[-DomainError, -Ctx] {
  def toRemote(ctx: Ctx)(err: Either[List[Throwable], ServerTransportError]): RemoteError

  protected def transformDomain(ctx: Ctx, domain: DomainError): RemoteError
}
