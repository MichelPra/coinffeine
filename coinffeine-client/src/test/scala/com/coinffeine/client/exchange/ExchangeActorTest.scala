package com.coinffeine.client.exchange

import java.util.concurrent.LinkedBlockingDeque
import scala.concurrent.duration._

import akka.actor.{ActorRef, Props, Terminated}
import akka.testkit.{TestActor, TestProbe}
import akka.testkit.TestActor.Message
import akka.util.Timeout
import org.scalatest.concurrent.Eventually

import com.coinffeine.client.CoinffeineClientTest
import com.coinffeine.client.CoinffeineClientTest.SellerPerspective
import com.coinffeine.client.exchange.ExchangeActor._
import com.coinffeine.client.handshake.MockExchangeProtocol
import com.coinffeine.client.handshake.HandshakeActor.{HandshakeFailure, HandshakeSuccess, StartHandshake}
import com.coinffeine.client.micropayment.MicroPaymentChannelActor
import com.coinffeine.client.paymentprocessor.MockPaymentProcessorFactory
import com.coinffeine.common.BitcoinjTest
import com.coinffeine.common.Currency.Euro
import com.coinffeine.common.bitcoin._
import com.coinffeine.common.blockchain.BlockchainActor._
import com.coinffeine.common.exchange.{Exchange, Role}
import com.coinffeine.common.protocol.ProtocolConstants

class ExchangeActorTest extends CoinffeineClientTest("buyerExchange")
  with SellerPerspective with BitcoinjTest with Eventually {

  implicit def testTimeout = new Timeout(5 second)
  private val protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitRefundSignatureTimeout = 1 second,
    refundSignatureAbortTimeout = 1 minute)

  private val handshakeActorMessageQueue = new LinkedBlockingDeque[Message]()
  private val handshakeProps = TestActor.props(handshakeActorMessageQueue)

  private val channel = new MockProtoMicroPaymentChannel(exchange)
  private def channelFactory(
      exchange: Exchange[Euro.type],
      role: Role,
      paymentProc: ActorRef,
      tx1: MutableTransaction,
      tx2: MutableTransaction): ProtoMicroPaymentChannel = channel
  private val exchangeActorMessageQueue = new LinkedBlockingDeque[Message]()
  private val exchangeProps = TestActor.props(exchangeActorMessageQueue)

  private val dummyTxId = new Hash(List.fill(64)("F").mkString)
  private val dummyTx = new MutableTransaction(network)
  private val dummyRefund = ImmutableTransaction(dummyTx)
  private val dummyPaymentProcessor = system.actorOf(
    new MockPaymentProcessorFactory(List.empty)
      .newProcessor(fiatAddress = "", initialBalance = Seq.empty)
  )

  trait Fixture {
    val listener = TestProbe()
    val blockchain = TestProbe()
    val wallet = createWallet(user.bitcoinKey, exchange.amounts.sellerDeposit)
    val actor = system.actorOf(Props(new ExchangeActor[Euro.type](
      handshakeProps,
      exchangeProps,
      new MockExchangeProtocol,
      channelFactory,
      protocolConstants,
      Set(listener.ref)
    )))
    listener.watch(actor)

    def withActor(actorName: String)(body: ActorRef => Unit) = {
      val actorSelection = system.actorSelection(actor.path / actorName)
      eventually {
        actorSelection.resolveOne().futureValue
      }
      whenReady(actorSelection.resolveOne())(body)
    }

    def startExchange(): Unit = {
      actor ! StartExchange(
        exchange, userRole, wallet, dummyPaymentProcessor, gateway.ref, blockchain.ref
      )
    }
  }

  "The exchange actor" should "report an exchange success if both handshake " +
      "and exchange work as expected" in new Fixture {
    startExchange()
    withActor(HandshakeActorName) { handshakeActor =>
      val queueItem = handshakeActorMessageQueue.pop()
      queueItem.msg shouldBe a [StartHandshake[_]]
      queueItem.sender should be (actor)
      actor.tell(HandshakeSuccess(dummyTxId, dummyTxId, dummyRefund), handshakeActor)
    }
    blockchain.expectMsg(WatchPublicKey(counterpart.bitcoinKey))
    blockchain.expectMsg(RetrieveTransaction(dummyTxId))
    blockchain.expectMsg(RetrieveTransaction(dummyTxId))
    blockchain.reply(TransactionFound(dummyTxId, dummyTx))
    blockchain.reply(TransactionFound(dummyTxId, dummyTx))

    withActor(MicroPaymentChannelActorName) { exchangeActor =>
      val queueItem = exchangeActorMessageQueue.pop()
      queueItem.msg should be (MicroPaymentChannelActor.StartMicroPaymentChannel(
        exchange, userRole, channel, protocolConstants, dummyPaymentProcessor, gateway.ref, Set(actor)))
      queueItem.sender should be (actor)
      actor.tell(MicroPaymentChannelActor.ExchangeSuccess, exchangeActor)
    }
    listener.expectMsg(ExchangeSuccess)
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the handshake fails" in new Fixture {
    startExchange()
    val error = new Error("Handshake error")
    withActor(HandshakeActorName) { handshakeActor =>
      val queueItem = handshakeActorMessageQueue.pop()
      queueItem.msg shouldBe a [StartHandshake[_]]
      queueItem.sender should be (actor)
      actor.tell(HandshakeFailure(error), handshakeActor)
    }
    listener.expectMsg(ExchangeFailure(error))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the blockchain can't find the commitment txs" in new Fixture {
    startExchange()
    withActor(HandshakeActorName) { handshakeActor =>
      val queueItem = handshakeActorMessageQueue.pop()
      queueItem.msg shouldBe a [StartHandshake[_]]
      queueItem.sender should be (actor)
      actor.tell(HandshakeSuccess(dummyTxId, dummyTxId, dummyRefund), handshakeActor)
    }
    blockchain.expectMsg(WatchPublicKey(counterpart.bitcoinKey))
    blockchain.expectMsg(RetrieveTransaction(dummyTxId))
    blockchain.expectMsg(RetrieveTransaction(dummyTxId))
    blockchain.reply(TransactionNotFound(dummyTxId))
    blockchain.reply(TransactionFound(dummyTxId, dummyTx))

    val error = new CommitmentTxNotInBlockChain(dummyTxId)
    listener.expectMsg(ExchangeFailure(error))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }

  it should "report a failure if the actual exchange fails" in new Fixture {
    startExchange()
    withActor(HandshakeActorName) { handshakeActor =>
      val queueItem = handshakeActorMessageQueue.pop()
      queueItem.msg shouldBe a [StartHandshake[_]]
      queueItem.sender should be (actor)
      actor.tell(HandshakeSuccess(dummyTxId, dummyTxId, dummyRefund), handshakeActor)
    }
    blockchain.expectMsg(WatchPublicKey(counterpart.bitcoinKey))
    blockchain.expectMsg(RetrieveTransaction(dummyTxId))
    blockchain.expectMsg(RetrieveTransaction(dummyTxId))
    blockchain.reply(TransactionFound(dummyTxId, dummyTx))
    blockchain.reply(TransactionFound(dummyTxId, dummyTx))

    val error = new Error("exchange failure")
    withActor(MicroPaymentChannelActorName) { exchangeActor =>
      val queueItem = exchangeActorMessageQueue.pop()
      queueItem.msg should be (MicroPaymentChannelActor.StartMicroPaymentChannel(
        exchange, userRole, channel, protocolConstants, dummyPaymentProcessor,
        gateway.ref, Set(actor)
      ))
      queueItem.sender should be (actor)
      actor.!(MicroPaymentChannelActor.ExchangeFailure(error, None))(exchangeActor)
    }
    listener.expectMsg(ExchangeFailure(error))
    listener.expectMsgClass(classOf[Terminated])
    system.stop(actor)
  }
}
