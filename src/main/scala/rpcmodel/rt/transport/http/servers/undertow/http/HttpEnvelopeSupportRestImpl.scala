package rpcmodel.rt.transport.http.servers.undertow.http

import io.circe.Json
import izumi.functional.bio.BIO
import izumi.functional.bio.BIO._
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.MethodId
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBase, GeneratedServerBaseImpl}
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.servers.shared.MethodIdExtractor
import rpcmodel.rt.transport.http.servers.undertow.MethodInput
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.rest.IRTRestSpec
import rpcmodel.rt.transport.rest.IRTRestSpec.{IRTBasicField, IRTPathSegment, IRTQueryParameterSpec, IRTType}
import rpcmodel.rt.transport.rest.RestSpec.OnWireGenericType

class HttpEnvelopeSupportRestImpl[F[+ _, + _] : BIO](idExtractor: MethodIdExtractor) extends HttpEnvelopeSupport[F] {
  override def makeInput(context: HttpRequestContext, dispatchers: Seq[GeneratedServerBaseImpl[F, _, Json]]): F[ServerTransportError, MethodInput] = {

    val idx: Map[GeneratedServerBase.MethodId, IRTRestSpec] = dispatchers.flatMap(_.specs.toSeq).toMap

    val maybeHandler = idx
      .toSeq
      .map {
        case (id, spec) =>
          matches(context, id, spec)
      }
      .headOption
      .flatten

    maybeHandler match {
      case Some(value) =>
        F.pure(value)
      case None =>
        for {
          id <- F.fromEither(idExtractor.extract(context.exchange.getRelativePath))
        } yield {
          MethodInput(context.body.json, id)
        }
    }
  }

  def matches(context: HttpRequestContext, id: MethodId, spec: IRTRestSpec): Option[MethodInput] = {
    val parts = context.exchange.getRelativePath.split('/')

    if (parts.length == spec.extractor.pathSpec.size) {
      val mapped = spec
        .extractor
        .pathSpec
        .zip(parts)
        .map {
          case (s, p) =>
            s match {
              case IRTPathSegment.Word(value) =>
                (value == p, None)
              case parameter: IRTPathSegment.Parameter =>
                convert(p, parameter)
            }
        }

      import scala.collection.JavaConverters._

      val mappedParams = spec.extractor.queryParameters.map {
        case (name, d) =>
          convert(Option(context.exchange.getQueryParameters.get(name)).map(_.asScala.toSeq), d)
      }

      val all = mapped ++ mappedParams
      if (all.forall(_._1)) {
        val pathPatch = all.flatMap(_._2.toSeq)
        val fullPatch = pathPatch.foldLeft(Json.obj()) {
          case (p, acc) =>
            acc.deepMerge(p)
        }
        Some(MethodInput(fullPatch, id))
      } else {
        None
      }
    } else {
      None
    }
  }

  def convert(value: String, p: IRTPathSegment.Parameter): (Boolean, Option[Json]) = {
    p.onWire match {
      case IRTRestSpec.OnWireScalar(ref) =>
        val out = convertScalar(Some(Seq(value)), p.path :+ p.field, ref)
        (out.isDefined, out)

      case IRTRestSpec.OnWireGeneric(tpe) =>
        tpe match {
          case OnWireGenericType.Option(tpe) =>
            convert(value, IRTPathSegment.Parameter(p.field, p.path, IRTRestSpec.OnWireScalar(tpe)))
          case _ =>
            (false, None)
        }
    }
  }

  def convert(value: Option[Seq[String]], p: IRTQueryParameterSpec): (Boolean, Option[Json]) = {
    val path = p.path :+ p.field
    p.onWire match {
      case IRTRestSpec.OnWireScalar(ref) =>
        val out = convertScalar(value, path, ref)
        (out.isDefined, out)

      case IRTRestSpec.OnWireGeneric(tpe) =>
        tpe match {
          case OnWireGenericType.Map(_, vref) =>
            val out = value.toSeq.flatten.flatMap(_.split(',')).map {
              s =>
                val parts = s.split('=')
                (parts.head, mapScalar(vref, parts.tail.mkString("=")))
            }

            (true, Some(Json.fromFields(out)))

          case OnWireGenericType.List(ref, unpacked) =>
            if (unpacked) {
              (true, Some(Json.fromValues(value.toSeq.flatten.map(mapScalar(ref, _)))))
            } else {
              (true, Some(Json.fromValues(value.toSeq.flatten.flatMap(_.split(',')).map(mapScalar(ref, _)))))
            }
          case OnWireGenericType.Option(ref) =>
            convertScalar(value, path, ref) match {
              case Some(value) =>
                (true, Some(value))
              case None =>
                (true, Some(merge(path, Json.Null)))
            }
        }
    }
  }

  private def convertScalar(value: Option[Seq[String]], path: Seq[IRTBasicField], ref: IRTType) = {
    value.flatMap(_.headOption) match {
      case Some(value) =>
        val jsonV = mapScalar(ref, value)
        Some(merge(path, jsonV))
      case None =>
        None
    }
  }

  private def mapScalar(ref: IRTType, value: String) = {
    ref match {
      case IRTType.IRTString =>
        Json.fromString(value)
      case IRTType.IRTInt =>
        Json.fromInt(Integer.parseInt(value))
    }
  }

  private def merge(path: Seq[IRTBasicField], jsonV: Json) = {
    path.foldRight(jsonV) {
      case (v, acc) =>
        Json.obj((v.name, acc))
    }
  }
}
