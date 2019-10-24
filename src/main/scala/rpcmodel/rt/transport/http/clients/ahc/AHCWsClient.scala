//package rpcmodel.rt.transport.http.clients.ahc
//
//import java.net.URL
//import java.util.UUID
//import java.util.function.BiFunction
//
//import io.circe.{Json, Printer}
//import izumi.functional.bio.BIO._
//import izumi.functional.bio.BIOAsync
//import org.asynchttpclient.ws.{WebSocket, WebSocketListener, WebSocketUpgradeHandler}
//import org.asynchttpclient.{AsyncHttpClient, BoundRequestBuilder, Response}
//import rpcmodel.rt.transport.codecs.IRTCodec
//import rpcmodel.rt.transport.dispatch.CtxDec
//import rpcmodel.rt.transport.dispatch.client.ClientTransport
//import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase
//import rpcmodel.rt.transport.dispatch.server.GeneratedServerBase.ClientResponse
//import rpcmodel.rt.transport.errors.ClientDispatcherError
//import rpcmodel.rt.transport.http.servers.undertow.WsEnvelope.{EnvelopeIn, InvokationId}
//import zio.Ref
//
//
//class AHCWsClient[F[+_, +_]: BIOAsync, C](c: AsyncHttpClient, target: URL, printer: Printer, ctx: CtxDec[F, ClientDispatcherError, Response, C]) extends ClientTransport[F, C, Json] {
//  override def dispatch(methodId: GeneratedServerBase.MethodId, body: Json): F[ClientDispatcherError, ClientResponse[C, Json]] = {
//    import io.circe.parser._
//    import io.circe.syntax._
//
//
//    for {
//      sock <- Ref.make(prepare())
//      s <- sock.get
//      id = UUID.randomUUID()
//      envelope = EnvelopeIn(methodId, Map.empty, body, InvokationId(id.toString))
//      _ <- F.sync(s.sendTextFrame(envelope.asJson.printWith(printer)))
//      resp <- F.async[ClientDispatcherError, Response] {
//        f =>
//          val handler = new BiFunction[Response, Throwable, Unit] {
//            override def apply(t: Response, u: Throwable): Unit = {
//              if (t != null) {
//                f(Right(t))
//              } else {
//                f(Left(ClientDispatcherError.UnknownException(u)))
//              }
//            }
//          }
//
//          prepare(methodId, body).execute().toCompletableFuture.handle[Unit](handler)
//          ()
//      }
//      c <- ctx.decode(resp)
//      body = resp.getResponseBody
//      parsed <- F.fromEither(parse(body))
//        .leftMap(e => ClientDispatcherError.ClientCodecFailure(List(IRTCodec.IRTCodecFailure.IRTParserException(e))))
//    } yield {
//      ClientResponse(c, parsed)
//    }
//  }
//
//  private val listener = new WebSocketListener() {
//    override def onOpen(websocket: WebSocket): Unit = {
//      //logger.debug(s"WS connection open: $websocket")
//    }
//
//    override def onClose(websocket: WebSocket, code: Int, reason: String): Unit = {
//      //logger.debug(s"WS connection closed: $websocket, $code, $reason")
//    }
//
//    override def onError(t: Throwable): Unit = {
//      //logger.debug(s"WS connection errored: $t")
//    }
//
//    override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit = {
//      //processFrame(payload)
//    }
//  }
//
//  private def prepare() = {
//    //val url = new URL(target.getProtocol, target.getHost, target.getPort, s"${target.getFile}/${methodId.service.name}/${methodId.method.name}")
//    import scala.collection.JavaConverters._
//
//    c.prepareGet(target.toString)
//      .execute(new WebSocketUpgradeHandler(List(listener).asJava))
//      .get()
//  }
//}
