package com.coinffeine.client

import akka.testkit.TestProbe

import com.coinffeine.common.{AkkaSpec, FiatCurrency, PeerConnection}
import com.coinffeine.common.exchange._
import com.coinffeine.common.protocol.gateway.MessageGateway.{ForwardMessage, ReceiveMessage}
import com.coinffeine.common.protocol.messages.PublicMessage

abstract class CoinffeineClientTest(systemName: String)
  extends AkkaSpec(systemName) with SampleExchangeInfo {

  val gateway = TestProbe()

  def fromBroker(message: PublicMessage) = ReceiveMessage(message, broker)

  protected class ValidateWithPeer(validation: PeerConnection => Unit) {
    def to(receiver: PeerConnection): Unit = validation(receiver)
  }

  def shouldForward(message: PublicMessage) =
    new ValidateWithPeer(receiver => gateway.expectMsg(ForwardMessage(message, receiver)))

  protected class ValidateAllMessagesWithPeer {
    private var messages: List[PeerConnection => Any] = List.empty
    def message(msg: PublicMessage): ValidateAllMessagesWithPeer = {
      messages = ((receiver: PeerConnection) => ForwardMessage(msg, receiver)) :: messages
      this
    }
    def to(receiver: PeerConnection): Unit = {
      gateway.expectMsgAllOf(messages.map(_(receiver)): _*)
    }
  }

  def shouldForwardAll = new ValidateAllMessagesWithPeer
}

object CoinffeineClientTest {

  trait Perspective {
    def exchange: Exchange[FiatCurrency]
    def userRole: Role
    def user = exchange.participants(userRole)
    def counterpart = exchange.participants(userRole.counterpart)
    def ongoingExchange = OngoingExchange(userRole, exchange)
    def counterpartConnection = exchange.connections(userRole.counterpart)
    def fromCounterpart(message: PublicMessage) = ReceiveMessage(message, counterpartConnection)
  }

  trait BuyerPerspective extends Perspective {
    val userRole = BuyerRole
  }

  trait SellerPerspective extends Perspective {
    val userRole = SellerRole
  }
}
