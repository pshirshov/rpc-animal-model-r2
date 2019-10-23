package rpcmodel.rt.transport.http

import izumi.functional.bio.BIOAsync
import rpcmodel.rt.transport.dispatch.GeneratedServerBase.{MethodId, ServerWireRequest, ServerWireResponse}
import rpcmodel.rt.transport.dispatch.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError

trait AbstractServerHandler[F[+ _, + _], C, WValue] {
  import izumi.functional.bio.BIO._
  protected implicit def bioAsync: BIOAsync[F]

  protected def dispatchers: Seq[GeneratedServerBaseImpl[F, C, Map[String, Seq[String]], WValue]]

  private val methods = dispatchers.map(d => d.id -> d.methods).toMap

  protected def call(headers: Map[String, Seq[String]], id: MethodId, decoded: WValue): F[ServerTransportError, ServerWireResponse[WValue]] = {
    for {
      svcm <- F.fromOption(ServerTransportError.MissingMethod(id))(methods.get(id.service))
      m <- F.fromOption(ServerTransportError.MissingMethod(id))(svcm.get(id))
      out <- m.apply(ServerWireRequest(headers, decoded)).leftMap(f => ServerTransportError.DispatcherError(f): ServerTransportError)
    } yield {
      out
    }
  }
}
