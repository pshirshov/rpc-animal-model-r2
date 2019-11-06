package rpcmodel.rt.transport.http.clients.ahc

import io.circe.Json
import rpcmodel.rt.transport.errors.ClientDispatcherError

trait ClientRequestHook[W, O] {
  def onRequest(c: W, request: W => O): Either[ClientDispatcherError, O]
}

object ClientRequestHook {
  def passthrough[W, T]: ClientRequestHook[W, T] = forCtx[W].passthrough[T]

  def forCtx[W]: ForCtx2PartiallyApplied[W] = new ForCtx2PartiallyApplied[W]

  final class ForCtx2PartiallyApplied[W](private val dummy: Boolean = false) extends AnyVal {
    def passthrough[T]: ClientRequestHook[W, T] = {
      (c: W, request: W => T) => Right(request(c))
    }
  }
}

sealed trait MappingError

object MappingError {

  case class ObjectExpected(currentPath: List[String], baseJson: Json) extends MappingError
  case class ArrayExpected(currentPath: List[String], baseJson: Json) extends MappingError
  case class ElementExpected(currentPath: List[String], baseJson: Json, name: String) extends MappingError
  case class UnexpectedEmptyRemoval(body: Json, removals: Seq[List[String]]) extends MappingError
  case class UnexpectedNonScalarEntity(currentPath: List[String], baseJson: Json, json: Json) extends MappingError

}