package rpcmodel.rt.transport.http.clients.ahc

case class WsClientContext(headers: Map[String, Seq[String]])

object WsClientContext {
  final val empty: WsClientContext = WsClientContext(Map.empty)
}