package rpcmodel.rt.transport.rest

import rpcmodel.rt.transport.rest.IRTRestSpec.{IRTBodySpec, IRTExtractorSpec, IRTType}
import rpcmodel.rt.transport.rest.RestSpec.{HttpMethod, OnWireGenericType, QueryParameterName}



// final annotation
final case class IRTRestSpec(method: HttpMethod, extractor: IRTExtractorSpec, body: IRTBodySpec)

object IRTRestSpec {
  sealed trait IRTType
  object IRTType {
    object IRTString extends IRTType
    object IRTInt extends IRTType
  }

  final case class IRTBodySpec(fields: Seq[IRTBodyParameter])
  final case class IRTBasicField(name: String)
  final case class IRTBodyParameter(field: IRTBasicField, path: Seq[IRTBasicField])

  final case class IRTExtractorSpec(
                                     queryParameters: Map[QueryParameterName, IRTQueryParameterSpec]
                                     , pathSpec: Seq[IRTPathSegment]
                                   )
  final case class IRTQueryParameterSpec(field: IRTBasicField, path: Seq[IRTBasicField], onWire: IRTOnWireType)

  sealed trait IRTPathSegment

  object IRTPathSegment {
    final case class Word(value: String) extends IRTPathSegment
    final case class Parameter(field: IRTBasicField, path: Seq[IRTBasicField], onWire: IRTOnWireType) extends IRTPathSegment
  }

  sealed trait IRTOnWireType
  final case class OnWireScalar(ref: IRTType) extends IRTOnWireType // will always be ref to builtin scalar
  final case class OnWireGeneric(tpe: OnWireGenericType) extends IRTOnWireType
}

object RestSpec {
  sealed trait OnWireGenericType
  object OnWireGenericType {
    case class Map(key: IRTType, value: IRTType) extends OnWireGenericType
    case class List(ref: IRTType, unpacked: Boolean) extends OnWireGenericType
    case class Option(ref: IRTType) extends OnWireGenericType
  }

  final case class QueryParameterName(value: String) extends AnyVal

  sealed trait HttpMethod {
    def name: String
  }

  object HttpMethod {
    final val all = List(Get, Post, Put, Delete, Patch)
      .map {
        m =>
          m.name.toLowerCase -> m
      }
      .toMap

    final case object Get extends HttpMethod {
      override def name: String = "Get"
    }

    final case object Post extends HttpMethod {
      override def name: String = "Post"
    }

    final case object Put extends HttpMethod {
      override def name: String = "Put"
    }

    final case object Delete extends HttpMethod {
      override def name: String = "Delete"
    }

    final case object Patch extends HttpMethod {
      override def name: String = "Patch"
    }

  }
}



