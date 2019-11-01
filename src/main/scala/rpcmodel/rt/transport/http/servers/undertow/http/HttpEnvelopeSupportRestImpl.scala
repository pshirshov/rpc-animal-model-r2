package rpcmodel.rt.transport.http.servers.undertow.http

import io.circe.Json
import izumi.functional.bio.BIO
import izumi.functional.bio.BIO._
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.MethodId
import rpcmodel.rt.transport.dispatch.server.{GeneratedServerBase, GeneratedServerBaseImpl}
import rpcmodel.rt.transport.errors.ServerTransportError
import rpcmodel.rt.transport.http.clients.ahc.Escaping
import rpcmodel.rt.transport.http.servers.shared.MethodIdExtractor
import rpcmodel.rt.transport.http.servers.undertow.MethodInput
import rpcmodel.rt.transport.http.servers.undertow.http.model.HttpRequestContext
import rpcmodel.rt.transport.rest.IRTRestSpec
import rpcmodel.rt.transport.rest.IRTRestSpec.{IRTBasicField, IRTPathSegment, IRTQueryParameterSpec, IRTType}
import rpcmodel.rt.transport.rest.RestSpec.OnWireGenericType

import scala.annotation.tailrec

class HttpEnvelopeSupportRestImpl[F[+ _, + _] : BIO]
(
  idExtractor: MethodIdExtractor,
  dispatchers: Seq[GeneratedServerBase[F, _, Json]],
) extends HttpEnvelopeSupport[F] {

  lazy val prefixes: PrefixTree[String, (MethodId, IRTRestSpec)] = {
    val allMethods = dispatchers.flatMap(_.specs.toSeq)
    val prefixed = allMethods.map {
      case (id, spec) =>
        (spec.extractor.pathSpec.takeWhile(_.isInstanceOf[IRTPathSegment.Word]).map(_.asInstanceOf[IRTPathSegment.Word].value), (id, spec))
    }

    PrefixTree.build(prefixed)
  }

  def indexesFor(path: String): Seq[(MethodId, IRTRestSpec)] = {
    prefixes.findSubtree(path.split('/').toList).map(_.subtreeValues).toSeq.flatten
  }

  override def makeInput(context: HttpRequestContext): F[ServerTransportError, MethodInput] = {
    val restCandidates = indexesFor(context.exchange.getRelativePath)
    println(s"${context.exchange.getQueryString}: REST mappings to test: $restCandidates")

    val maybeRest = restCandidates
      .map {
        case (id, spec) =>
          matches(context, id, spec)
      }
      .find(_.isDefined)
      .flatten

    maybeRest match {
      case Some(value) =>
        F.pure(value)
      case None =>
        mapRpc(context)
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
        val out: Option[Json] = tpe match {
          case OnWireGenericType.Map(_, vref) =>
            val out = value.toSeq.flatten.flatMap(_.split(',')).map {
              s =>
                val parts = s.split('=')
                val key = Escaping.unescape(parts.head)
                val value = Escaping.unescape(parts.tail.mkString("="))
                mapScalar(vref, value).map(j => (key, j))
            }
            flatten(out).map(Json.fromFields)

          case OnWireGenericType.List(ref, unpacked) =>
            val v = if (unpacked) {
              flatten(value.toSeq.flatten.map(Escaping.unescape).map(mapScalar(ref, _)))
            } else {
              flatten(value.toSeq.flatten.map(Escaping.unescape).flatMap(_.split(',')).map(mapScalar(ref, _)))
            }
            v.map(Json.fromValues)

          case OnWireGenericType.Option(ref) =>
            convertScalar(value, path, ref) match {
              case Some(value) =>
                Some(value)
              case None =>
                Some(Json.Null)
            }
        }
        out match {
          case Some(value) =>
            (true, Some(merge(path, value)))
          case None =>
            (false, None)
        }
    }
  }

  def flatten[T](values: Seq[Option[T]]): Option[Seq[T]] = {
    if (values.forall(_.isDefined)) {
      Some(values.collect({ case Some(v) => v }))
    } else {
      None
    }
  }

  private def convertScalar(value: Option[Seq[String]], path: Seq[IRTBasicField], ref: IRTType): Option[Json] = {
    for {
      v <- value
      first <- v.headOption
      jsonV <- mapScalar(ref, first)
    } yield {
      merge(path, jsonV)
    }
  }

  private def mapScalar(ref: IRTType, value: String): Option[Json] = {
    try {
      ref match {
        case IRTType.IRTString =>
          Some(Json.fromString(value))
        case IRTType.IRTInteger =>
          Some(Json.fromLong(java.lang.Long.parseLong(value)))
        case IRTType.IRTBool =>
          Some(Json.fromBoolean(java.lang.Boolean.parseBoolean(value)))
        case IRTType.IRTDouble =>
          Json.fromDouble(java.lang.Double.parseDouble(value))
      }
    } catch {
      case _: Throwable =>
        None
    }
  }

  private def merge(path: Seq[IRTBasicField], jsonV: Json): Json = {
    path.foldRight(jsonV) {
      case (v, acc) =>
        Json.obj((v.name, acc))
    }
  }
}
