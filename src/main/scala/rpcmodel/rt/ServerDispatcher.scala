package rpcmodel.rt

import izumi.functional.bio.{BIO, BIOError}
import rpcmodel.rt.ServerDispatcher._
import rpcmodel.rt.IRTCodec.IRTCodecFailure

trait ServerContext[F[_, _], C, WCtxIn, WValue] {
  type Req = ServerWireRequest[WCtxIn, WValue]
  type Res = ServerWireResponse[WValue]
}

trait ServerHook[F[_, _], C, WCtxIn, WValue] extends ServerContext[F, C, WCtxIn, WValue] {
  def onCtxDecode(r: Req, next: (Req) => F[ServerDispatcherError, C]): F[ServerDispatcherError, C] = {
    next(r)
  }

  def onDecode[T](r: Req, c: C, next: (Req, C) => F[ServerDispatcherError, T]): F[ServerDispatcherError, T] = {
    next(r, c)
  }

  def onEncode[T, B](r: Req, c: C, b: B, t: T, next: (Req, C, B, T) => F[ServerDispatcherError, WValue]): F[ServerDispatcherError, WValue] = {
    next(r, c, b, t)
  }
}

object ServerHook {
  def nothing[F[_, _], C, WCtxIn, WValue]: ServerHook[F, C, WCtxIn, WValue] = new ServerHook[F, C, WCtxIn, WValue] {}
}



trait ServerDispatcher[F[_, _], C, WCtxIn, WValue] extends ServerContext[F, C, WCtxIn, WValue] {
  def hook: ServerHook[F,C, WCtxIn, WValue]
  def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]]
  def methods: Map[MethodId, Req => F[ServerDispatcherError, Res]]

  protected def doDecode[V : IRTCodec[*, WValue]](r: Req, c: C): F[ServerDispatcherError, V]
}

abstract class DispatherBaseImpl[F[+_, +_] : BIOError, C, WCtxIn, WValue]
(

) extends ServerDispatcher[F, C, WCtxIn, WValue] {
  import BIO._

  override final def dispatch(methodId: MethodId, r: Req): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    methods.get(methodId) match {
      case Some(value) =>
        value(r)
      case None =>
        F.fail(MethodHandlerMissing(methodId))
    }
  }

  protected final def doDecode[V : IRTCodec[*, WValue]](r: Req, c: C): F[ServerDispatcherError, V] = {
    val codec = implicitly[IRTCodec[V, WValue]]
    hook.onDecode(r, c, (req, _) => F.fromEither(codec.decode(req.value).left.map(f => CodecFailure(f))))
  }

  protected final def doEncode[T : IRTCodec[*, WValue], B](r: Req, c: C, b: B, t: T): F[ServerDispatcherError, ServerWireResponse[WValue]] = {
    val codec = implicitly[IRTCodec[T, WValue]]
    for {
      out <- hook.onEncode(r, c, b, t, (_: Req, _: C, _: B, t: T) => F.pure(codec.encode(t)))
    } yield {
      ServerWireResponse(out)
    }
  }
}

object ServerDispatcher {
  //case class ClientRequest[C, V](c: C, value: V, methodId: MethodId)
  case class ClientResponse[WCtxIn, WValue](c: WCtxIn, value: WValue)

  case class ServerWireRequest[WCtxIn, WValue](c: WCtxIn, value: WValue)
  case class ServerWireResponse[WValue](value: WValue)

  sealed trait ServerDispatcherError
  case class MethodHandlerMissing(methodId: MethodId) extends ServerDispatcherError
  case class CodecFailure(failures: List[IRTCodecFailure]) extends ServerDispatcherError

  sealed trait ClientDispatcherError
  case class UnknownException(t: Throwable) extends ClientDispatcherError
  case class ServerError(s: ServerDispatcherError) extends ClientDispatcherError
  case class CodecFailure1(failures: List[IRTCodecFailure]) extends ClientDispatcherError

  case class ClientDispatcherException(error: ClientDispatcherError) extends RuntimeException

  sealed trait RPCResult[+B, +G] {
    def toEither: Either[B, G]
  }
  object RPCResult {
    case class Good[+G](value: G) extends RPCResult[Nothing, G] {
      override def toEither: Either[Nothing, G] = Right(value)
    }
    case class Bad[+B](value: B) extends RPCResult[B, Nothing] {
      override def toEither: Either[B, Nothing] = Left(value)
    }
  }

  case class MethodName(name: String) extends AnyVal
  case class ServiceName(name: String) extends AnyVal
  case class MethodId(service: ServiceName, method: MethodName)
}

trait CtxDec[F[_, _], E, WC, C] {
  def decode(c: WC): F[E, C]
}
