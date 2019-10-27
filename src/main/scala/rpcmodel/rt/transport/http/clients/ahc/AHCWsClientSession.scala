package rpcmodel.rt.transport.http.clients.ahc

import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.netty.ws.NettyWebSocket
import org.asynchttpclient.ws.{WebSocket, WebSocketListener, WebSocketUpgradeHandler}


trait AHCWSListener {
  def onTextMessage(payload: String): Unit
}

class AHCWsClientSession(
                          client: AsyncHttpClient,
                          target: URI,
                          serverListener: AHCWSListener,
                        ) {
  class Listener extends WebSocketListener {
    override def onOpen(websocket: WebSocket): Unit = {}

    override def onClose(websocket: WebSocket, code: Int, reason: String): Unit = {}

    override def onError(t: Throwable): Unit = {}

    override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit = {
      serverListener.onTextMessage(payload)
    }
  }
  private val listener = new Listener()

  private val sess = new AtomicReference[NettyWebSocket](null)

  def get(): NettyWebSocket = {
    Option(sess.get()) match {
      case Some(value) if value.isOpen =>
        value
      case _ =>
        this.synchronized {
          Option(sess.get()) match {
            case Some(value) if value.isOpen =>
              value
            case _ =>
              val conn = prepare()
              sess.set(conn)
              conn
          }
        }
    }
  }

  private def prepare(): NettyWebSocket = {
    client.prepareGet(target.toString)
      .execute(new WebSocketUpgradeHandler(Collections.singletonList(listener)))
      .get()
  }
}
