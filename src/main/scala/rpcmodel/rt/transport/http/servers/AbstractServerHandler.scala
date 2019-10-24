package rpcmodel.rt.transport.http.servers

import izumi.functional.bio.BIOAsync
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.{MethodId, ServerWireRequest, ServerWireResponse}
import rpcmodel.rt.transport.dispatch.CtxDec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError

trait AbstractServerHandler[F[+ _, + _], C, WCtxIn, WValue] {

  import izumi.functional.bio.BIO._

  protected implicit def bioAsync: BIOAsync[F]

  protected def dispatchers: Seq[GeneratedServerBaseImpl[F, C, WValue]]

  protected def dec: CtxDec[F, ServerTransportError, WCtxIn, C]

  private val methods = dispatchers
    .groupBy(_.id)
    .mapValues {
      d =>
        if (d.size > 1) {
          throw new RuntimeException(s"Duplicated services: $d")
        }
        d.head
    }
    .toMap

  protected def call(headers: WCtxIn, id: MethodId, decoded: WValue): F[ServerTransportError, ServerWireResponse[WValue]] = {
    for {
      svcm <- F.fromOption(ServerTransportError.MissingService(id))(methods.get(id.service))
      ctx <- dec.decode(headers)
      out <- svcm.dispatch(id, ServerWireRequest(ctx, decoded)).leftMap(f => ServerTransportError.DispatcherError(f): ServerTransportError)
    } yield {
      out
    }
  }
}
