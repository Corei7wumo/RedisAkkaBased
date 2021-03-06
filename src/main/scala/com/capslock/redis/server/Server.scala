package com.capslock.redis.server

import akka.NotUsed
import akka.actor._
import akka.stream._
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import akka.util._
import com.capslock.redis.cache.CacheManager
import com.capslock.redis.client.ClientSession
import com.capslock.redis.command.RespCommand
import com.capslock.redis.command.response.RESP
import com.capslock.redis.server.protocol.{ProtocolPacketHandler, ProtocolParser}

import scala.util._

object Server {

  def logicFlow(conn: Tcp.IncomingConnection, cacheManager: ActorRef)(implicit system: ActorSystem): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder ⇒
      import GraphDSL.Implicits._

      val sessionClient = system.actorOf(ClientSession.props(cacheManager))
      val sessionClientSource = Source.fromPublisher(ActorPublisher[RespCommand](sessionClient))
      val session = builder.add(sessionClientSource)

      val mapRespFlow: Flow[RespCommand, ByteString, NotUsed] =
        Flow[RespCommand].map(respCommand => ByteString(RESP.encode(respCommand.resp)))

      val packetHandler = builder.add(Flow.fromGraph(new ProtocolParser()).via(new ProtocolPacketHandler(sessionClient)))

      val delimiter = builder.add(Flow[ByteString]
        .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
        .map(_.utf8String)
        .map { msg => msg.substring(0, msg.length - 1) })

      val merge = builder.add(Merge[RespCommand](2))
      val mapResp = builder.add(mapRespFlow)
      delimiter ~> packetHandler ~> merge
                         session ~> merge ~> mapResp

      FlowShape(delimiter.in, mapResp.out)
    })

  def mkServer(address: String, port: Int, cacheManager: ActorRef)(implicit system: ActorSystem, materializer: Materializer): Unit = {
    import system.dispatcher

    val connectionHandler = Sink.foreach[Tcp.IncomingConnection] { conn ⇒
      println(s"Incoming connection from: ${conn.remoteAddress}")
      conn.handleWith(logicFlow(conn, cacheManager))
    }
    val incomingConnections = Tcp().bind(address, port)
    val binding = incomingConnections.to(connectionHandler).run()

    binding onComplete {
      case Success(b) ⇒
        println(s"Server started, listening on: ${b.localAddress}")
      case Failure(e) ⇒
        println(s"Server could not be bound to $address:$port: ${e.getMessage}")
    }
  }

  def startServer(address: String, port: Int) = {
    implicit val system = ActorSystem("Server")
    implicit val materializer = ActorMaterializer()
    implicit val cacheManager = system.actorOf(Props[CacheManager])

    mkServer(address, port, cacheManager)
  }
}