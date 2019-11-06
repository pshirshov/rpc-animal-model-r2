package rpcmodel.rt.transport.http.servers.undertow.http.model

import io.undertow.server.HttpServerExchange
import rpcmodel.rt.transport.http.servers.undertow.HttpBody

case class HttpRequestContext(
                               exchange: HttpServerExchange,
                               body: HttpBody,
                             ) {

  import scala.jdk.CollectionConverters._

  def headers: Map[String, Seq[String]] = {
    val nativeHeaders = exchange.getRequestHeaders
    nativeHeaders
      .getHeaderNames
      .asScala
      .map {
        n =>
          (n.toString, nativeHeaders.get(n).iterator().asScala.toSeq)
      }
      .toMap
  }
}
