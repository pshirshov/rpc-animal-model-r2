package rpcmodel.rt.transport.errors

import io.circe.Json

sealed trait MappingError

object MappingError {

  case class ObjectExpected(currentPath: List[String], baseJson: Json) extends MappingError
  case class ArrayExpected(currentPath: List[String], baseJson: Json) extends MappingError
  case class ElementExpected(currentPath: List[String], baseJson: Json, name: String) extends MappingError
  case class UnexpectedEmptyRemoval(body: Json, removals: Seq[List[String]]) extends MappingError
  case class UnexpectedNonScalarEntity(currentPath: List[String], baseJson: Json, json: Json) extends MappingError

}