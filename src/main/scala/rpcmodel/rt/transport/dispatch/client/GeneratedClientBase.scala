package rpcmodel.rt.transport.dispatch.client

import izumi.functional.bio.BIOError
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.errors.ClientDispatcherError.ClientCodecFailure

abstract class GeneratedClientBase[F[+ _, + _] : BIOError, C, WValue] {

  import izumi.functional.bio.BIO._

  def transport: ClientTransport[F, C, WValue]

  protected final def doDecode[V: IRTCodec[*, WValue]](r: ClientResponse[WValue]): F[ClientDispatcherError, V] = {
    val codecRes = implicitly[IRTCodec[V, WValue]]

    F.fromEither(codecRes.decode(r.value).left.map(f => ClientCodecFailure(f)))
  }
}
