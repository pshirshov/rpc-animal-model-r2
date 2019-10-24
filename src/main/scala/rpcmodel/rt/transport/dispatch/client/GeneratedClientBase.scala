package rpcmodel.rt.transport.dispatch.client

import izumi.functional.bio.BIOError
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.errors.ClientDispatcherError.ClientCodecFailure

abstract class GeneratedClientBase[F[+ _, + _] : BIOError, C, WCtxIn, WValue] {

  import izumi.functional.bio.BIO._

  def hook: ClientHook[F, WCtxIn, WValue] = ClientHook.nothing

  protected final def doDecode[V: IRTCodec[*, WValue]](r: ClientResponse[WCtxIn, WValue]): F[ClientDispatcherError, V] = {
    val codecRes = implicitly[IRTCodec[V, WValue]]

    hook.onDecode(r, r => F.fromEither(codecRes.decode(r.value).left.map(f => ClientCodecFailure(f))))

  }
}
