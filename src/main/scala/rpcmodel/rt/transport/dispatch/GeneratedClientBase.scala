package rpcmodel.rt.transport.dispatch

import izumi.functional.bio.BIOError
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.GeneratedServerBase.ClientResponse
import rpcmodel.rt.transport.errors.ClientDispatcherError
import rpcmodel.rt.transport.errors.ClientDispatcherError.ClientCodecFailure

abstract class GeneratedClientBase[F[+ _, + _] : BIOError, C, WValue] {

  import izumi.functional.bio.BIO._

  def hook: ClientHook[F, C, WValue] = ClientHook.nothing

  protected final def doDecode[V: IRTCodec[*, WValue]](r: ClientResponse[C, WValue]): F[ClientDispatcherError, V] = {
    val codecRes = implicitly[IRTCodec[V, WValue]]

    hook.onDecode(r, r => F.fromEither(codecRes.decode(r.value).left.map(f => ClientCodecFailure(f))))

  }
}
