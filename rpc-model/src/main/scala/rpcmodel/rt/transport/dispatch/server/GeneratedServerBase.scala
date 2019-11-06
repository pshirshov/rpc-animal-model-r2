package rpcmodel.rt.transport.dispatch.server

import izumi.functional.bio.{BIO, BIOError}
import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase._
import rpcmodel.rt.transport.errors.ServerDispatcherError.{MethodHandlerMissing, ServerCodecFailure}
import rpcmodel.rt.transport.errors.{ClientDispatcherError, ServerDispatcherError}
import rpcmodel.rt.transport.rest.IRTRestSpec

trait GeneratedServerBase[+F[_, _], -C, WValue] extends ServerContext[F, C, WValue] {
  def id: ServiceName
  def methods: Map[MethodId, Req => F[ServerDispatcherError, Res]]
  def specs: Map[MethodId, IRTRestSpec]
  def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]]
}

abstract class GeneratedServerBaseImpl[F[+ _, + _] : BIOError, C, WValue] extends GeneratedServerBase[F, C, WValue] {

  import BIO._

  override final def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    methods.get(methodId) match {
      case Some(value) =>
        value(r)
      case None =>
        F.fail(MethodHandlerMissing(methodId))
    }
  }

  protected final def doDecode[V: IRTCodec[*, WValue]](r: Req): F[ServerDispatcherError, V] = {
    val codec = implicitly[IRTCodec[V, WValue]]
    F.fromEither(codec.decode(r.value).left.map(f => ServerCodecFailure(f)))
  }

  protected final def doEncode[ResBody: IRTCodec[*, WValue], ReqBody: IRTCodec[*, WValue]]
    (
      r: Req,
      reqBody: ReqBody,
      resBody: ResBody,
      kind: ResponseKind,
    ): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    val codec = implicitly[IRTCodec[ResBody, WValue]]
    for {
      out <- F.pure(codec.encode(resBody))
    } yield {
      ServerWireResponse(out, kind)
    }
  }
}

object GeneratedServerBase {

  case class ClientResponse[+WValue](value: WValue)

  case class ServerWireRequest[+WCtxIn, +WValue](c: WCtxIn, value: WValue)

  sealed trait ResponseKind
  object ResponseKind {
    object Scalar extends ResponseKind
    object RpcSuccess extends ResponseKind
    object RpcFailure extends ResponseKind
  }
  case class ServerWireResponse[+WValue](value: WValue, kind: ResponseKind)

  case class ClientDispatcherException(error: ClientDispatcherError) extends RuntimeException

  case class MethodName(name: String) extends AnyVal
  case class ServiceName(name: String) extends AnyVal
  case class MethodId(service: ServiceName, method: MethodName)

}




