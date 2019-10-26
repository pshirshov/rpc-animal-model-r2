package rpcmodel.rt.transport.http.servers.shared

import rpcmodel.rt.transport.errors.ServerTransportError

trait TransportErrorHandler[DomainError, Ctx] {
  def transformError(ctx: Ctx, error: ServerTransportError.Predefined): TransportResponse.Failure
  def transformCritical(ctx: Ctx, critical: List[Throwable]): TransportResponse.UnexpectedFailure
  def transformDomain(ctx: Ctx, domain: DomainError): TransportResponse

  final def onError(ctx: Ctx)(err: Either[List[Throwable], ServerTransportError]): TransportResponse = err match {
    case Left(value) =>
      transformCritical(ctx, value)

    case Right(value) =>
      value match {
        case ServerTransportError.DomainError(value) =>
          transformDomain(ctx, value.asInstanceOf[DomainError])
        case o: ServerTransportError.Predefined =>
          transformError(ctx, o)
      }
  }
}
