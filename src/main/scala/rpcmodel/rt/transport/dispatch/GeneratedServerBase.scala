package rpcmodel.rt.transport.dispatch

import izumi.functional.bio.{BIO, BIOError}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.GeneratedServerBase._
import rpcmodel.rt.transport.errors.ServerDispatcherError.{MethodHandlerMissing, ServerCodecFailure}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerDispatcherError}


trait GeneratedServerBase[F[_, _], C, WCtxIn, WValue] extends ServerContext[F, C, WCtxIn, WValue] {
  def id: ServiceName
  def methods: Map[MethodId, Req => F[ServerDispatcherError, Res]]
  def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]]
}

abstract class GeneratedServerBaseImpl[F[+ _, + _] : BIOError, C, WCtxIn, WValue]
(

) extends GeneratedServerBase[F, C, WCtxIn, WValue] {

  import BIO._

  def hook: ServerHook[F, C, WCtxIn, WValue]

  override final def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    methods.get(methodId) match {
      case Some(value) =>
        value(r)
      case None =>
        F.fail(MethodHandlerMissing(methodId))
    }
  }

  protected final def doDecode[V: IRTCodec[*, WValue]](r: Req, c: C): F[ServerDispatcherError, V] = {
    val codec = implicitly[IRTCodec[V, WValue]]
    hook.onDecode(r, c, (req, _) => F.fromEither(codec.decode(req.value).left.map(f => ServerCodecFailure(f))))
  }

  protected final def doEncode[ResBody: IRTCodec[*, WValue], ReqBody: IRTCodec[*, WValue]](r: Req, c: C, reqBody: ReqBody, resBody: ResBody): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    val codec = implicitly[IRTCodec[ResBody, WValue]]
    for {
      out <- hook.onEncode(r, c, reqBody, resBody, (_: Req, _: C, _: ReqBody, rb: ResBody) => F.pure(codec.encode(rb)))
    } yield {
      ServerWireResponse(out)
    }
  }
}


object GeneratedServerBase {

  case class ClientResponse[WCtxIn, WValue](c: WCtxIn, value: WValue)

  case class ServerWireRequest[WCtxIn, WValue](c: WCtxIn, value: WValue)

  case class ServerWireResponse[WValue](value: WValue)

  case class ClientDispatcherException(error: ClientDispatcherError) extends RuntimeException



  case class MethodName(name: String) extends AnyVal

  case class ServiceName(name: String) extends AnyVal

  case class MethodId(service: ServiceName, method: MethodName)

}




