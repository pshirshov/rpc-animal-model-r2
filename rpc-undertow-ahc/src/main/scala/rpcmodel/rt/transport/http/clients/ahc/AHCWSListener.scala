package rpcmodel.rt.transport.http.clients.ahc

protected[clients] trait AHCWSListener {
  def onTextMessage(payload: String): Unit
}
