package rpcmodel.rt.transport.dispatch.server

import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.errors.ServerDispatcherError

trait ServerHook[F[+_, +_], C, WValue] extends ServerContext[F, C, WValue] {
  def onCtxDecode(r: Req, next: (Req) => F[ServerDispatcherError, C]): F[ServerDispatcherError, C] = {
    next(r)
  }

  def onDecode[T: IRTCodec[*, WValue]](r: Req, next: Req => F[ServerDispatcherError, T]): F[ServerDispatcherError, T] = {
    next(r)
  }

  def onEncode[ReqBody : IRTCodec[*, WValue], ResBody: IRTCodec[*, WValue]](r: Req, reqBody: ResBody, resBody: ReqBody, next: (Req, ResBody, ReqBody) => F[ServerDispatcherError, WValue]): F[ServerDispatcherError, WValue] = {
    next(r, reqBody, resBody)
  }
}

object ServerHook {
  def nothing[F[+_, +_], C, WValue]: ServerHook[F, C, WValue] = new ServerHook[F, C, WValue] {}
}