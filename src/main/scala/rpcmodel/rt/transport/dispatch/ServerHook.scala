package rpcmodel.rt.transport.dispatch

import rpcmodel.rt.transport.codecs.IRTCodec
import rpcmodel.rt.transport.errors.ServerDispatcherError

trait ServerHook[F[+_, +_], C, WCtxIn, WValue] extends ServerContext[F, C, WCtxIn, WValue] {
  def onCtxDecode(r: Req, next: (Req) => F[ServerDispatcherError, C]): F[ServerDispatcherError, C] = {
    next(r)
  }

  def onDecode[T: IRTCodec[*, WValue]](r: Req, c: C, next: (Req, C) => F[ServerDispatcherError, T]): F[ServerDispatcherError, T] = {
    next(r, c)
  }

  def onEncode[ReqBody : IRTCodec[*, WValue], ResBody: IRTCodec[*, WValue]](r: Req, c: C, reqBody: ResBody, resBody: ReqBody, next: (Req, C, ResBody, ReqBody) => F[ServerDispatcherError, WValue]): F[ServerDispatcherError, WValue] = {
    next(r, c, reqBody, resBody)
  }
}

object ServerHook {
  def nothing[F[+_, +_], C, WCtxIn, WValue]: ServerHook[F, C, WCtxIn, WValue] = new ServerHook[F, C, WCtxIn, WValue] {}
}