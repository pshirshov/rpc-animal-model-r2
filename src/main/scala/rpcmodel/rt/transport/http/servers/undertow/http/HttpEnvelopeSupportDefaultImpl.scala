package rpcmodel.rt.transport.http.servers.undertow.http

import io.circe.Json
import izumi.functional.bio.BIO
import izumi.functional.bio.BIO._
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBaseImpl
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.MethodIdExtractor
import rpcmodel.rt.transport.http.servers.undertow.MethodInput
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.rest.IRTRestSpec

class HttpEnvelopeSupportDefaultImpl[F[+ _, + _] : BIO](idExtractor: MethodIdExtractor) extends HttpEnvelopeSupport[F] {
  override def makeInput(context: HttpRequestContext, dispatchers: Seq[GeneratedServerBaseImpl[F, _, Json]]): F[ServerTransportError, MethodInput] = {
    for {
      id <- F.fromEither(idExtractor.extract(context.exchange.getRelativePath))
    } yield {
      MethodInput(context.body.json, id)
    }
  }
}



