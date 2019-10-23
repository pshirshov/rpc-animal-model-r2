package rpcmodel.rt.transport.dispatch

import izumi.functional.bio.BIOError
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.errors.ClientDispatcherError.ClientCodecFailure

abstract class GeneratedClientBase[F[+ _, + _] : BIOError, C, WCtxIn, WValue] {

  import izumi.functional.bio.BIO._

  def hook: ClientHook[F, C, WCtxIn, WValue] = ClientHook.nothing

  protected final def doDecode[V: IRTCodec[*, WValue]](r: ClientResponse[WCtxIn, WValue], c: C): F[ClientDispatcherError, V] = {
    val codecRes = implicitly[IRTCodec[V, WValue]]

    hook.onDecode(r, c, (_, r) => F.fromEither(codecRes.decode(r.value).left.map(f => ClientCodecFailure(f))))

  }
}
