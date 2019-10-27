package rpcmodel.rt.transport.http.servers.undertow.ws

import izumi.functional.bio.BIO
import izumi.functional.bio.BIO._
import rpcmodel.rt.transport.http.servers.shared.{InvokationId, PendingResponse, WsSessionId}

class WsSessionBuzzer[F[+ _, + _] : BIO, Meta](session: WebsocketSession[F, Meta, _, _]) {
  def id: WsSessionId = session.id

  def meta: Meta = session.meta.get()

  def send(value: String): F[Throwable, Unit] = session.doSend(value)

  def takePending(id: InvokationId): F[Nothing, Option[PendingResponse]] = {
    F.sync(session.pending.getOrDefault(id, None))
  }

  def dropPending(id: InvokationId): F[Nothing, Unit] = {
    F.sync(Option(session.pending.remove(id)))
  }

  def setPending(id: InvokationId): F[Nothing, Unit] = F.sync {
    session.pending.put(id, None)
  }

  def disconnect(): F[Throwable, Unit] = session.disconnect()
}

