package rpcmodel.rt.transport.http.clients.ahc

import java.net.{URI, URLDecoder, URLEncoder}

import io.circe.Json
import org.asynchttpclient.BoundRequestBuilder
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.MethodId
import rpcmodel.rt.transport.rest.IRTRestSpec
import rpcmodel.rt.transport.rest.IRTRestSpec.IRTPathSegment
import rpcmodel.rt.transport.rest.RestSpec.{HttpMethod, OnWireGenericType}

import scala.annotation.tailrec

object Escaping {
  @inline final def escape(s: String): String = URLEncoder.encode(s, "UTF-8")
  @inline final def unescape(s: String): String = URLDecoder.decode(s, "UTF-8")
}

class RestRequestHook[F[+ _, + _], RC]
(
  methods: Map[MethodId, IRTRestSpec],
) extends ClientRequestHook[AHCClientContext, RC, BoundRequestBuilder] {

  override def onRequest(c: AHCClientContext[RC], request: AHCClientContext[RC] => BoundRequestBuilder): BoundRequestBuilder = {
    methods.get(c.methodId) match {
      case Some(value) =>
        try {
          processRest(c, value)
        } catch {
          case t: Throwable =>
            t.printStackTrace()
            throw t
        }

      case None =>
        request(c)
    }
  }

  def cleanup(body: Json, removals: Seq[List[String]]): Json = {
    body.asObject match {
      case Some(value) =>
        val (toRemove, toDig) = removals.partition(_.size == 1)

        val nextGroups = toDig
          .map {
            case head :: tail =>
              (head, tail)
          }

        val next = nextGroups
          .groupBy(_._1)
          .mapValues(_.map(_._2))
          .toSeq
          .flatMap {
            case (sub, r) =>
              value.apply(sub).map(s => (sub, cleanup(s, r))).toSeq
          }

        val leave = value.toMap.removedAll(nextGroups.map(_._1))

        Json.fromFields((next ++ leave).toMap.removedAll(toRemove.map(_.head)))
      case None =>
        body
    }
  }

  private def processRest(c: AHCClientContext[RC], value: IRTRestSpec): BoundRequestBuilder = {
    val removals = value.extractor.pathSpec.collect {
      case IRTPathSegment.Parameter(field, path, _) =>
        (path :+ field).map(_.name).toList
    } ++ value.extractor.queryParameters.toSeq.map {
      case (_, v) =>
        (v.path :+ v.field).map(_.name).toList
    }

    val newbody = cleanup(c.body, removals)

    val newPath = value.extractor.pathSpec
      .map {
        case IRTPathSegment.Word(value) =>
          value
        case IRTPathSegment.Parameter(field, path, _) =>
          extract((path :+ field).map(_.name).toList, c.body)
      }

    val url = new URI(
      c.target.getScheme,
      c.target.getUserInfo,
      c.target.getHost,
      c.target.getPort,
      c.target.getPath + newPath.mkString("/"),
      c.target.getQuery,
      c.target.getFragment
    )

    val params: Map[String, List[String]] = value.extractor.queryParameters.map {
      case (k, v) =>
        val path = (v.path :+ v.field).map(_.name).toList

        val values = v.onWire match {
          case IRTRestSpec.OnWireScalar(_) =>
            List(extract(path, c.body))
          case IRTRestSpec.OnWireGeneric(tpe) =>
            tpe match {
              case OnWireGenericType.Map(_, _) =>
                val elements = extractMap(path, c.body)
                List(elements.map {
                  case (k, v) =>
                    s"${Escaping.escape(k)}=${Escaping.escape(v)}"
                }.mkString(","))

              case OnWireGenericType.List(_, unpacked) =>
                val elements = extractList(path, c.body)
                if (unpacked) {
                  elements
                } else {
                  List(elements.map(Escaping.escape).mkString(","))
                }
              case OnWireGenericType.Option(_) =>
                List(extractMaybe(path, c.body).getOrElse(""))
            }
        }

        (k.value, values)
    }

    import scala.collection.JavaConverters._

    println(s"transformed: ${c.body} => ${value.method.name}, $newPath, $params, $newbody")
    val base = c.client.prepare(value.method.name.toUpperCase, url.toString)
      .setQueryParams(params.mapValues(_.asJava).toMap.asJava)

    value.method match {
      case HttpMethod.Get =>
        base
      case _ =>
        base.setBody(c.printer.print(newbody))
    }

  }

  @tailrec
  private def extract(path: List[String], json: Json): String = {
    path match {
      case Nil =>
        foldScalar(json)
      case head :: tail =>
        extract(tail, json.asObject.get.apply(head).get)
    }
  }

  @tailrec
  private def extractMap(path: List[String], json: Json): Map[String, String] = {
    path match {
      case Nil =>
        json.asObject.get.toMap.mapValues(foldScalar).toMap

      case head :: tail =>
        extractMap(tail, json.asObject.get.apply(head).get)
    }
  }

  @tailrec
  private def extractList(path: List[String], json: Json): List[String] = {
    path match {
      case Nil =>
        json.asArray.get.map(foldScalar).toList

      case head :: tail =>
        extractList(tail, json.asObject.get.apply(head).get)
    }
  }

  @tailrec
  private def extractMaybe(path: List[String], json: Json): Option[String] = {
    path match {
      case Nil =>
        Some(foldScalar(json))
      case head :: tail =>
        json.asObject.get.apply(head) match {
          case Some(value) =>
            extractMaybe(tail, value)
          case None =>
            None
        }
    }
  }

  private def foldScalar(json: Json) = {
    json.fold(
      ???,
      b => b.toString,
      n => n.toString,
      s => s,
      a => ???,
      o => ???,
    )
  }
}
