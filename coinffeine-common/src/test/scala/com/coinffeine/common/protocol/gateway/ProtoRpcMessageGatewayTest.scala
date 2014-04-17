package com.coinffeine.common.protocol.gateway

import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.testkit.{TestActorRef, TestProbe}
import com.googlecode.protobuf.pro.duplex.PeerInfo
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

import com.coinffeine.common.{AkkaSpec, DefaultTcpPortAllocator, PeerConnection}
import com.coinffeine.common.network.UnitTestNetworkComponent
import com.coinffeine.common.protocol.TestClient
import com.coinffeine.common.protocol.gateway.MessageGateway.{Bind, BoundTo, ReceiveMessage}
import com.coinffeine.common.protocol.messages.brokerage.OrderMatch
import com.coinffeine.common.protocol.serialization._

class ProtoRpcMessageGatewayTest extends AkkaSpec("MessageGatewaySystem")
  with Eventually with IntegrationPatience {

  val receiveTimeout = 10.seconds

  "Protobuf RPC Message gateway" must "send a known message to a remote peer" in new FreshGateway {
    val (message, protoMessage) = randomMessageAndSerialization()
    gateway ! MessageGateway.ForwardMessage(message, remotePeerConnection)
    eventually {
      remotePeer.receivedMessagesNumber should be (1)
      remotePeer.receivedMessages contains protoMessage
    }
  }

  it must "send a known message twice reusing the connection to the remote peer" in
    new FreshGateway {
      val (msg1, msg2) = (randomMessage(), randomMessage())
      gateway ! MessageGateway.ForwardMessage(msg1, remotePeerConnection)
      gateway ! MessageGateway.ForwardMessage(msg2, remotePeerConnection)
      eventually {
        remotePeer.receivedMessagesNumber should be (2)
        remotePeer.receivedMessages contains protocolSerialization.toProtobuf(msg1)
        remotePeer.receivedMessages contains protocolSerialization.toProtobuf(msg2)
      }
    }

  it must "throw while forwarding when recipient was never connected" in new FreshGateway {
    val msg = randomMessage()
    remotePeer.shutdown()
    a [MessageGateway.ForwardException] should be thrownBy {
      testGateway.receive(MessageGateway.ForwardMessage(msg, remotePeerConnection))
    }
  }

  val subscribeToOrderMatches = MessageGateway.Subscribe {
    case ReceiveMessage(msg: OrderMatch, _) => true
    case _ => false
  }

  it must "deliver messages to subscribers when filter match" in new FreshGateway {
    val msg = randomMessage()
    gateway ! subscribeToOrderMatches
    remotePeer.sendMessage(msg)
    expectMsg(receiveTimeout, ReceiveMessage(msg, remotePeer.connection))
  }

  it must "do not deliver messages to subscribers when filter doesn't match" in new FreshGateway {
    val msg = randomMessage()
    gateway ! MessageGateway.Subscribe(msg => false)
    remotePeer.sendMessage(msg)
    expectNoMsg()
  }

  it must "deliver messages to several subscribers when filter match" in new FreshGateway {
    val msg = randomMessage()
    val subs = for (i <- 1 to 5) yield TestProbe()
    subs.foreach(_.send(gateway, subscribeToOrderMatches))
    remotePeer.sendMessage(msg)
    subs.foreach(_.expectMsg(receiveTimeout, ReceiveMessage(msg, remotePeer.connection)))
  }

  trait FreshGateway extends ProtoRpcMessageGateway.Component
      with TestProtocolSerializationComponent with UnitTestNetworkComponent {
    val (localPeerAddress, gateway) = createGateway()
    val (remotePeerAddress, remotePeer) = createRemotePeer(localPeerAddress)
    val remotePeerConnection = new PeerConnection(
      remotePeerAddress.getHostName, remotePeerAddress.getPort)
    val testGateway = createGatewayTestActor

    private def createGateway(): (PeerInfo, ActorRef) = {
      val peerInfo = allocateLocalPeerInfo()
      val ref = system.actorOf(messageGatewayProps)
      eventually {
        ref ! Bind(peerInfo)
        expectMsg(BoundTo(peerInfo))
      }
      (peerInfo, ref)
    }

    private def createGatewayTestActor: TestActorRef[ProtoRpcMessageGateway] = {
      val peerInfo = allocateLocalPeerInfo()
      val ref = TestActorRef(new ProtoRpcMessageGateway(protocolSerialization))
      eventually {
        ref ! Bind(peerInfo)
        expectMsg(BoundTo(peerInfo))
      }
      ref
    }

    private def createRemotePeer(localPeerAddress: PeerInfo): (PeerInfo, TestClient) = {
      val peerInfo = allocateLocalPeerInfo()
      eventually {
        val client = new TestClient(peerInfo.getPort, localPeerAddress, protocolSerialization)
        client.connectToServer()
        (peerInfo, client)
      }
    }

    private def allocateLocalPeerInfo() =
      new PeerInfo("localhost", DefaultTcpPortAllocator.allocatePort())
  }
}
