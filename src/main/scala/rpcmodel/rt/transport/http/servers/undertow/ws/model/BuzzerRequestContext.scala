package rpcmodel.rt.transport.http.servers.undertow.ws.model

case class BuzzerRequestContext(headers: Map[String, Seq[String]])

object BuzzerRequestContext {
  final val empty: BuzzerRequestContext = BuzzerRequestContext(Map.empty)
}