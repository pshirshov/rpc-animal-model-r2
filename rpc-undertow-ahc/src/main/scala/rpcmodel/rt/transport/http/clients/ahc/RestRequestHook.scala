package rpcmodel.rt.transport.http.clients.ahc

import java.net.{URI, URLDecoder, URLEncoder}

import io.circe.Json
import org.asynchttpclient.BoundRequestBuilder
import izumi.functional.IzEither._
import rpcmodel.rt.transport.dispatch.client.ClientRequestHook
import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.MethodId
import rpcmodel.rt.transport.errors.MappingError.UnexpectedEmptyRemoval
import rpcmodel.rt.transport.errors.{ClientDispatcherError, MappingError}
import rpcmodel.rt.transport.rest.IRTRestSpec
import rpcmodel.rt.transport.rest.IRTRestSpec.IRTPathSegment
import rpcmodel.rt.transport.rest.RestSpec.{HttpMethod, OnWireGenericType}

object Escaping {
  @inline final def escape(s: String): String = URLEncoder.encode(s, "UTF-8")

  @inline final def unescape(s: String): String = URLDecoder.decode(s, "UTF-8")
}

class RestRequestHook[F[+ _, + _], RC]
(
  methods: Map[MethodId, IRTRestSpec],
) extends ClientRequestHook[AHCClientContext[RC], BoundRequestBuilder] {

  override def onRequest(c: AHCClientContext[RC], request: AHCClientContext[RC] => BoundRequestBuilder): Either[ClientDispatcherError, BoundRequestBuilder] = {
    methods.get(c.methodId) match {
      case Some(value) =>
        processRest(c, value).left.map(e => ClientDispatcherError.RestMappingError(e))

      case None =>
        Right(request(c))
    }
  }

  def cleanup(body: Json, removals: Seq[List[String]]): Either[List[MappingError], Json] = {
    body.asObject match {
      case Some(value) =>
        val (toRemove, toDig) = removals.partition(_.size == 1)
        for {
          nextGroups <- toDig
            .map {
              case Nil =>
                Left(List(UnexpectedEmptyRemoval(body, removals)))
              case head :: tail =>
                Right((head, tail))
            }.biAggregate
          next <- nextGroups
            .groupBy(_._1)
            .mapValues(_.map(_._2))
            .toSeq
            .flatMap {
              case (sub, r) =>
                value.apply(sub)
                  .map {
                    s => cleanup(s, r).map(r => (sub, r))
                  }
                  .toSeq
            }
            .biAggregate

        } yield {
          val leave = value.toMap.removedAll(nextGroups.map(_._1))
          Json.fromFields((next ++ leave).toMap.removedAll(toRemove.map(_.head)))
        }

      case None =>
        Right(body)
    }
  }

  private def processRest(c: AHCClientContext[RC], value: IRTRestSpec): Either[List[MappingError], BoundRequestBuilder] = {
    val removals = value.extractor.pathSpec.collect {
      case IRTPathSegment.Parameter(field, path, _) =>
        (path :+ field).map(_.name).toList
    } ++ value.extractor.queryParameters.toSeq.map {
      case (_, v) =>
        (v.path :+ v.field).map(_.name).toList
    }


    val newPath = value.extractor.pathSpec
      .map {
        case IRTPathSegment.Word(value) =>
          Right(value)
        case IRTPathSegment.Parameter(field, path, _) =>
          extract(List.empty, c.body)((path :+ field).map(_.name).toList, c.body)
      }.biAggregate


    val params = value.extractor.queryParameters
      .toSeq
      .map {
        case (k, v) =>
          val path = (v.path :+ v.field).map(_.name).toList

          val values = v.onWire match {
            case IRTRestSpec.OnWireScalar(_) =>
              for {
                l <- extract(List.empty, c.body)(path, c.body)
              } yield {
                List(l)
              }
            case IRTRestSpec.OnWireGeneric(tpe) =>
              tpe match {
                case OnWireGenericType.Map(_, _) =>
                  val elements = extractMap(List.empty, c.body)(path, c.body)
                  for {
                    m <- elements
                  } yield {
                    List(m.map {
                      case (k, v) =>
                        s"${Escaping.escape(k)}=${Escaping.escape(v)}"
                    }.mkString(","))
                  }

                case OnWireGenericType.List(_, unpacked) =>
                  val elements = extractList(List.empty, c.body)(path, c.body)
                  if (unpacked) {
                    elements
                  } else {
                    for {
                      l <- elements
                    } yield {
                      List(l.map(Escaping.escape).mkString(","))
                    }

                  }
                case OnWireGenericType.Option(_) =>
                  for {
                    l <- extractMaybe(List.empty, c.body)(path, c.body)
                  } yield {
                    List(l.getOrElse(""))
                  }
              }
          }

          for {
            v <- values
          } yield {
            (k.value, v)
          }
      }
      .toList
      .biAggregate
      .map(_.toMap)

    import scala.collection.JavaConverters._

    for {
      parameters <- params
      np <- newPath
      newbody <- cleanup(c.body, removals)
    } yield {
      val url = new URI(
        c.target.getScheme,
        c.target.getUserInfo,
        c.target.getHost,
        c.target.getPort,
        c.target.getPath + np.mkString("/"),
        c.target.getQuery,
        c.target.getFragment
      )


      println(s"transformed: ${c.body} => ${value.method.name}, $newPath, $params, $newbody")
      val base = c.client.prepare(value.method.name.toUpperCase, url.toString)
        .setQueryParams(parameters.mapValues(_.asJava).toMap.asJava)

      value.method match {
        case HttpMethod.Get =>
          base
        case _ =>
          base.setBody(c.printer.print(newbody))
      }
    }
  }

  private def extract(currentPath: List[String], baseJson: Json)(path: List[String], json: Json): Either[List[MappingError], String] = {
    path match {
      case Nil =>
        foldScalar(currentPath, baseJson)(json)
      case head :: tail =>
        for {
          obj <- json.asObject.toRight(List(MappingError.ObjectExpected(currentPath, baseJson)))
          el <- obj.apply(head).toRight(List(MappingError.ElementExpected(currentPath, baseJson, head)))
          v <- extract(currentPath :+ head, baseJson)(tail, el)
        } yield {
          v
        }
    }
  }


  private def extractMap(currentPath: List[String], baseJson: Json)(path: List[String], json: Json): Either[List[MappingError], Map[String, String]] = {
    path match {
      case Nil =>
        for {
          obj <- json.asObject.toRight(List(MappingError.ObjectExpected(currentPath, baseJson)))
          v <- obj.toMap
            .toSeq
            .map {
              case (k, v) =>

                foldScalar(currentPath, baseJson)(v).map(j => (k, j))
            }
            .biAggregate
            .map(_.toMap)
        } yield {
          v
        }

      case head :: tail =>
        for {
          obj <- json.asObject.toRight(List(MappingError.ObjectExpected(currentPath, baseJson)))
          el <- obj.apply(head).toRight(List(MappingError.ElementExpected(currentPath, baseJson, head)))
          v <- extractMap(currentPath :+ head, baseJson)(tail, el)
        } yield {
          v
        }

    }
  }

  private def extractList(currentPath: List[String], baseJson: Json)(path: List[String], json: Json): Either[List[MappingError], List[String]] = {
    path match {
      case Nil =>
        for {
          arr <- json.asArray.toRight(List(MappingError.ArrayExpected(currentPath, baseJson)))
          v <- arr.map(foldScalar(currentPath, baseJson)).toList.biAggregate
        } yield {
          v
        }

      case head :: tail =>
        for {
          obj <- json.asObject.toRight(List(MappingError.ObjectExpected(currentPath, baseJson)))
          el <- obj.apply(head).toRight(List(MappingError.ElementExpected(currentPath, baseJson, head)))
          v <- extractList(currentPath :+ head, baseJson)(tail, el)
        } yield {
          v
        }
    }
  }

  private def extractMaybe(currentPath: List[String], baseJson: Json)(path: List[String], json: Json): Either[List[MappingError], Option[String]] = {
    path match {
      case Nil =>
        for {
          value <- foldScalar(currentPath, baseJson)(json)
        } yield {
          Some(value)
        }
      case head :: tail =>
        for {
          obj <- json.asObject.toRight(List(MappingError.ObjectExpected(currentPath, baseJson)))
          v <- obj.apply(head) match {
            case Some(value) =>
              extractMaybe(currentPath :+ head, baseJson)(tail, value)
            case None =>
              Right(None)
          }
        } yield {
          v
        }
    }
  }

  private def foldScalar(currentPath: List[String], baseJson: Json)(json: Json): Either[List[MappingError], String] = {
    def onError = Left(List(MappingError.UnexpectedNonScalarEntity(currentPath, baseJson, json)))

    json.fold(
      onError,
      b => Right(b.toString),
      n => Right(n.toString),
      s => Right(s),
      _ => onError,
      _ => onError,
    )
  }
}

