package rpcmodel.rt.transport.http.servers.undertow

import java.util.concurrent.ConcurrentHashMap

import izumi.functional.bio.{BIOAsync, BIORunner}
import scala.collection.JavaConverters._

class SessionManager[F[+ _, + _] : BIOAsync : BIORunner, Meta] {
  private val sessions = new ConcurrentHashMap[WsSessionId, WebsocketSession[F, Meta, _, _]]

  final def register(value: WebsocketSession[F, Meta, _, _]): Unit = {
    sessions.put(value.id, value)
  }

  final def drop(id: WsSessionId): Unit = {
    sessions.remove(id)
  }

  final def filterSessions(pred: Meta => Boolean): Seq[WsBuzzer[F, Meta]] = {
    sessions
      .asScala
      .values
      .filter(s => pred(s.meta.get()))
      .map(s => new WsBuzzer(s))
      .toSeq
  }
}
