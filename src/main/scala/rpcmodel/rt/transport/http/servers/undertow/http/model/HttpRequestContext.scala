package rpcmodel.rt.transport.http.servers.undertow.http.model

import io.circe.Json
import io.undertow.server.HttpServerExchange

case class HttpRequestContext(
                               exchange: HttpServerExchange,
                               body: Array[Byte],
                               decoded: Json
                             ) {

  import scala.collection.JavaConverters._

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
