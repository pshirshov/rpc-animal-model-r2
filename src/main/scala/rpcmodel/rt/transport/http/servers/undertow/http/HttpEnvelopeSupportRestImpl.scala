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

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}


class HttpEnvelopeSupportRestImpl[F[+ _, + _] : BIO](idExtractor: MethodIdExtractor, dispatchers: Seq[GeneratedServerBaseImpl[F, _, Json]]) extends HttpEnvelopeSupport[F] {

  lazy val prefixes: PrefixTree[String, (MethodId, IRTRestSpec)] = {
    val allMethods = dispatchers.flatMap(_.specs.toSeq)
    val prefixed = allMethods.map {
      case (id, spec) =>
        spec.extractor.pathSpec.takeWhile(_.isInstanceOf[IRTPathSegment.Word]).map(_.asInstanceOf[IRTPathSegment.Word].value) -> (id, spec)
    }

    PrefixTree.build(prefixed)
  }

  def indexesFor(path: String): Seq[(MethodId, IRTRestSpec)] = {
    prefixes.findSubtree(path.split('/').toList).map(_.subtreeValues).toSeq.flatten
  }

  override def makeInput(context: HttpRequestContext): F[ServerTransportError, MethodInput] = {

    val restCandidates = indexesFor(context.exchange.getRelativePath)
    println(s"REST mappings to test: $restCandidates")
    
    if (restCandidates.isEmpty) {
      mapRpc(context)
    } else {
      val maybeHandler = Try {
        restCandidates
          .map {
            case (id, spec) =>
              matches(context, id, spec)
          }
          .find(_.isDefined)
          .flatten


      }
      maybeHandler match {
        case Success(Some(value)) =>
          F.pure(value)
        case o =>
          o match {
            case Failure(exception) =>
              exception.printStackTrace()
            case Success(_) =>
          }
          mapRpc(context)
      }
    }
  }

  private def mapRpc(context: HttpRequestContext): F[ServerTransportError, MethodInput] = {
    for {
      id <- F.fromEither(idExtractor.extract(context.exchange.getRelativePath))
    } yield {
      MethodInput(context.body.json, id)
    }
  }

  def matches(context: HttpRequestContext, id: MethodId, spec: IRTRestSpec): Option[MethodInput] = {
    val parts = context.exchange.getRelativePath.split('/')

    if (parts.length == spec.extractor.pathSpec.size) {
      val mappedPath = spec
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

      val mappedParams = spec.extractor.queryParameters.toSeq.map {
        case (name, d) =>
          convert(Option(context.exchange.getQueryParameters.get(name.value)).map(_.asScala.toSeq), d)
      }

      println(s"mapped path: ${context.exchange.getRelativePath}?${context.exchange.getQueryString} => ${id}, $mappedPath, $mappedParams")

      val all = mappedPath ++ mappedParams
      if (all.forall(_._1)) {
        val pathPatch = all.flatMap(_._2.toSeq)
        val fullPatch = pathPatch.foldLeft(context.body.json) {
          case (p, acc) =>
            acc.deepMerge(p)
        }
        println(s"Body: ${context.body.json} => $fullPatch")
        Some(MethodInput(fullPatch, id))
      } else {
        None
      }
    } else {
      None
    }
  }

  @tailrec
  private def convert(value: String, p: IRTPathSegment.Parameter): (Boolean, Option[Json]) = {
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

  private def convert(value: Option[Seq[String]], p: IRTQueryParameterSpec): (Boolean, Option[Json]) = {
    val path = p.path :+ p.field
    p.onWire match {
      case IRTRestSpec.OnWireScalar(ref) =>
        val out = convertScalar(value, path, ref)
        (out.isDefined, out)

      case IRTRestSpec.OnWireGeneric(tpe) =>
        val out = tpe match {
          case OnWireGenericType.Map(_, vref) =>
            val out = value.toSeq.flatten.flatMap(_.split(',')).map {
              s =>
                val parts = s.split('=')
                (parts.head, mapScalar(vref, parts.tail.mkString("=")))
            }
            Json.fromFields(out)

          case OnWireGenericType.List(ref, unpacked) =>
            if (unpacked) {
              Json.fromValues(value.toSeq.flatten.map(mapScalar(ref, _)))
            } else {
              Json.fromValues(value.toSeq.flatten.flatMap(_.split(',')).map(mapScalar(ref, _)))
            }
          case OnWireGenericType.Option(ref) =>
            convertScalar(value, path, ref) match {
              case Some(value) =>
                value
              case None =>
                Json.Null
            }
        }
        (true, Some(merge(path, out)))
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
