package rpcmodel.rt.transport.http.servers.undertow.ws

import izumi.functional.bio.BIO
import izumi.functional.bio.BIO._
import izumi.fundamentals.platform.language.Quirks._
import rpcmodel.rt.transport.dispatch.server.{InvokationId, PendingResponse, WsSessionId}

protected[transport] class WsSessionBuzzer[F[+ _, + _] : BIO, Meta]
(
  session: WebsocketSession[F, Meta, _, _],
) {
  def id: WsSessionId = session.id

  def meta: Meta = session.meta.get()

  def send(value: String): F[Throwable, Unit] = {
    session.doSend(value)
  }

  def disconnect(): F[Throwable, Unit] = {
    session.disconnect()
  }

  def takePending(id: InvokationId): F[Nothing, Option[PendingResponse]] = F.sync {
    session.pending.getOrDefault(id, None)
  }

  def dropPending(id: InvokationId): F[Nothing, Unit] = F.sync {
    session.pending.remove(id).discard()
  }

  def setPending(id: InvokationId): F[Nothing, Unit] = F.sync {
    session.pending.put(id, None).discard()
  }
}

