package rpcmodel.rt.transport.http.servers.shared

import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.Envelopes.RemoteError

trait TransportErrorHandler[DomainError, Ctx] {
  def toRemote(ctx: Ctx)(err: Either[List[Throwable], ServerTransportError]): RemoteError

  protected def transformDomain(ctx: Ctx, domain: DomainError): RemoteError
}
