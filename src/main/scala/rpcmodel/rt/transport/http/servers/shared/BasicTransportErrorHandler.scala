package rpcmodel.rt.transport.http.servers.shared

import io.circe.Json
import io.circe.syntax._
import rpcmodel.rt.transport.errors.{ServerDispatcherError, ServerTransportError}

abstract class BasicTransportErrorHandler[DomainError, Ctx] extends TransportErrorHandler[DomainError, Ctx] {
  // TODO: withTraces = true
  override def transformError(ctx: Ctx, error: ServerTransportError.Predefined): TransportResponse.Failure = {
    val reason = error match {
      case f: ServerTransportError.DispatcherError =>
        f.e match {
          case f1: ServerDispatcherError.MethodHandlerMissing =>
            Map("reason" -> Json.fromString(s"Missng handler: ${f1.methodId}"))
          case f1: ServerDispatcherError.ServerCodecFailure =>
            val f = f1.failures.map {
             f =>
                Json.fromString(f.toString)
            }
            Map("reason" -> Json.fromString(s"Failed to decode request body"), "failures" -> f.asJson)
        }
      case f: ServerTransportError.TransportException =>
        Map("reason" -> Json.fromString(s"Transport exception: ${f.e.getMessage}"))
      case f: ServerTransportError.MethodIdError =>
        Map("reason" -> Json.fromString(s"Can't find method id in: ${f.path}"))
      case f: ServerTransportError.MissingService =>
        Map("reason" -> Json.fromString(s"No service found for method: ${f.id}"))
      case f: ServerTransportError.JsonCodecError =>
        Map("reason" -> Json.fromString(s"Cannot decode JSON: ${f.s}: ${f.e.getMessage}"))
      case f: ServerTransportError.EnvelopeFormatError =>
        Map("reason" -> Json.fromString(s"Cannot decode envelope: ${f.s}: ${f.e.getMessage}"))
    }

    val genericDiag = Map("type" -> Json.fromString(error.getClass.getSimpleName))
    val out: Map[String, Json] = reason ++ genericDiag
    TransportResponse.Failure(out .asJson)
  }

  override def transformCritical(ctx: Ctx, critical: List[Throwable]): TransportResponse.UnexpectedFailure = {
    TransportResponse.UnexpectedFailure(Json.obj())
  }
}

object BasicTransportErrorHandler {
  def withoutDomain[Ctx]: BasicTransportErrorHandler[Nothing, Ctx] = new BasicTransportErrorHandler[Any, Ctx] {
    override def transformDomain(ctx: Ctx, domain: Any): TransportResponse =throw new RuntimeException()
  }.asInstanceOf[BasicTransportErrorHandler[Nothing, Ctx]]

}