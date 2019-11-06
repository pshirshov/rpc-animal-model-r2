package rpcmodel.rt.transport.http.servers.undertow.http

import izumi.functional.bio.BIO
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.MethodIdExtractor
import rpcmodel.rt.transport.http.servers.undertow.MethodInput
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext

trait HttpEnvelopeSupport[+F[_, _]] {
  def makeInput(context: HttpRequestContext): F[ServerTransportError, MethodInput]
}

object HttpEnvelopeSupport {
  def default[F[+ _, + _] : BIO]: HttpEnvelopeSupport[F] = new HttpEnvelopeSupportDefaultImpl[F](MethodIdExtractor.TailImpl)
}
