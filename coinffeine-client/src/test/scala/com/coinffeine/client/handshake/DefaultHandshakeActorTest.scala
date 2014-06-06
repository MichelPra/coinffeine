package com.coinffeine.client.handshake

import java.math.BigInteger
import scala.util.{Failure, Success}

import akka.actor.Props
import akka.testkit.TestProbe
import com.google.bitcoin.core.Transaction
import com.google.bitcoin.crypto.TransactionSignature
import org.scalatest.mock.MockitoSugar

import com.coinffeine.client.CoinffeineClientTest
import com.coinffeine.client.handshake.HandshakeActor.StartHandshake
import com.coinffeine.common.Currency
import com.coinffeine.common.protocol._
import com.coinffeine.common.protocol.gateway.MessageGateway.ReceiveMessage
import com.coinffeine.common.protocol.messages.handshake.{RefundTxSignatureRequest, RefundTxSignatureResponse}

/** Test fixture for testing the handshake actor interaction, one derived class per scenario. */
abstract class DefaultHandshakeActorTest(systemName: String)
  extends CoinffeineClientTest(systemName) with MockitoSugar {

  class MockHandshake extends Handshake[Currency.Euro.type] {
    override val exchangeInfo = sampleExchangeInfo
    override val commitmentTransaction = MockTransaction()
    override val refundTransaction = MockTransaction()
    val counterpartCommitmentTransaction = MockTransaction()
    val counterpartRefund = MockTransaction()
    val invalidRefundTransaction = MockTransaction()

    val refundSignature = new TransactionSignature(BigInteger.ZERO, BigInteger.ZERO)
    val counterpartRefundSignature = new TransactionSignature(BigInteger.ONE, BigInteger.ONE)

    override def signCounterpartRefundTransaction(txToSign: Transaction) =
      if (txToSign == counterpartRefund) Success(counterpartRefundSignature)
      else Failure(new Error("Invalid refundSig"))

    override def validateRefundSignature(sig: TransactionSignature) =
      if (sig == refundSignature) Success(()) else Failure(new Error("Invalid signature!"))
  }

  def protocolConstants: ProtocolConstants

  val handshake = new MockHandshake
  override val counterpart = handshake.exchangeInfo.counterpart
  override val broker = handshake.exchangeInfo.broker
  val listener = TestProbe()
  val blockchain = TestProbe()
  val actor = system.actorOf(
    Props(new DefaultHandshakeActor(handshake, protocolConstants)), "handshake-actor")
  listener.watch(actor)

  def givenActorIsInitialized(): Unit =
    actor ! StartHandshake(gateway.ref, blockchain.ref, Set(listener.ref))

  def shouldForwardRefundSignatureRequest(): Unit = {
    val refundSignatureRequest = RefundTxSignatureRequest("id", handshake.refundTransaction)
    shouldForward (refundSignatureRequest) to counterpart
  }

  def shouldSignCounterpartRefund(): Unit = {
    val request = RefundTxSignatureRequest("id", handshake.counterpartRefund)
    gateway.send(actor, ReceiveMessage(request, handshake.exchangeInfo.counterpart))
    val refundSignatureRequest = RefundTxSignatureResponse("id", handshake.counterpartRefundSignature)
    shouldForward (refundSignatureRequest) to counterpart
  }
}
