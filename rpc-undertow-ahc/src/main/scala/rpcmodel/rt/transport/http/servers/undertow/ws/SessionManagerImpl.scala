package rpcmodel.rt.transport.http.servers.undertow.ws

import java.util.concurrent.ConcurrentHashMap

import izumi.fundamentals.platform.language.Quirks._
import rpcmodel.rt.transport.dispatch.server.WsSessionId

import scala.collection.JavaConverters._

protected[undertow] final class SessionManagerImpl[F[+ _, + _], Meta] extends SessionManager[F, Meta] {
  private val sessions = new ConcurrentHashMap[WsSessionId, WebsocketSession[F, Meta, _, _]]

  def register(value: WebsocketSession[F, Meta, _, _]): Unit = {
    sessions.put(value.id, value).discard()
  }

  def drop(id: WsSessionId): Unit = {
    sessions.remove(id).discard()
  }

  def filterSessions(pred: Meta => Boolean): Seq[WsSessionBuzzer[F, Meta]] = {
    sessions
      .asScala
      .values
      .filter(s => pred(s.meta.get()))
      .map(s => s.makeBuzzer())
      .toSeq
  }
}
