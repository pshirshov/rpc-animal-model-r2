package rpcmodel.rt.transport.http.servers.shared

import izumi.functional.bio.BIOAsync
import rpcmodel.rt.transport.dispatch.ContextProvider
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{MethodId, ServerWireRequest, ServerWireResponse}
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError

trait AbstractServerHandler[F[+ _, + _], TransportContext, ServerTransportContext, WireBody] {

  import izumi.functional.bio.BIO._

  protected implicit def bioAsync: BIOAsync[F]

  protected def dispatchers: Seq[GeneratedServerBaseImpl[F, TransportContext, WireBody]]

  protected def serverContextProvider: ContextProvider[F, ServerTransportError, ServerTransportContext, TransportContext]

  private lazy val methods = dispatchers
    .groupBy(_.id)
    .mapValues {
      d =>
        if (d.size > 1) {
          throw new RuntimeException(s"Duplicated services: $d")
        }
        d.head
    }
    .toMap

  protected def call(headers: ServerTransportContext, id: MethodId, decoded: WireBody): F[ServerTransportError, ServerWireResponse[WireBody]] = {
    for {
      svcm <- F.fromOption(ServerTransportError.MissingService(id))(methods.get(id.service))
      ctx <- serverContextProvider.decode(headers)
      out <- svcm.dispatch(id, ServerWireRequest(ctx, decoded)).leftMap(f => ServerTransportError.DispatcherError(f): ServerTransportError)
    } yield {
      out
    }
  }
}
