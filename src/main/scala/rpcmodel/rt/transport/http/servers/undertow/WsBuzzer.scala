package rpcmodel.rt.transport.http.servers.undertow

import izumi.functional.bio.{BIOAsync, BIORunner}
import izumi.functional.bio.BIO._
import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeOut, InvokationId}

class WsBuzzer[F[+ _, + _] : BIOAsync : BIORunner, Meta](session: WebsocketSession[F, Meta, _, _]) {
  def id: WsSessionId = session.id

  def meta: Meta = session.meta.get()

  def send(value: String): F[Throwable, Unit] = session.doSend(value)

  def takePending(id: InvokationId): F[Nothing, Option[PendingResponse]] = F.sync(Option(session.pending.remove(id)))


}
