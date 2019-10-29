package rpcmodel.rt.transport.http.servers.undertow

import io.undertow.server.HttpServerExchange
import io.undertow.websockets.core.BufferedTextMessage
import izumi.functional.bio.BIOExit
import rpcmodel.rt.transport.http.servers.undertow.ws.model.WsConnection

trait RuntimeErrorHandler[T] {

  def onCritical(context: RuntimeErrorHandler.Context, value: List[Throwable]): Unit = {}

  def onDomain(context: RuntimeErrorHandler.Context, value: T): Unit = {}

  final def handle(context: RuntimeErrorHandler.Context)(f: BIOExit[T, _]): Unit = {
    f match {
      case BIOExit.Success(_) =>
      case failure: BIOExit.Failure[_] =>
        failure.asInstanceOf[BIOExit.Failure[T]].toEither match {
          case Left(value) =>
            onCritical(context, value)
          case Right(value) =>
            onDomain(context, value)
        }
    }
  }
}

object RuntimeErrorHandler {
  def ignore[T]: RuntimeErrorHandler[T] = new RuntimeErrorHandler[T] {}

  def print[T]: RuntimeErrorHandler[T] = new RuntimeErrorHandler[T] {
    override def onCritical(context: Context, value: List[Throwable]): Unit = {
      System.err.println(s"Unhandled error in $context")
      value.foreach(_.printStackTrace())
    }

    override def onDomain(context: Context, value: T): Unit = {
      System.err.println(s"Unhandled error in $context: $value")
    }
  }

  sealed trait Context

  object Context {

    case class WebsocketServerSession(ctx: WsConnection, message: BufferedTextMessage) extends Context

    case class WebsocketClientSession() extends Context

    case class HttpRequest(exchange: HttpServerExchange) extends Context

  }

}