package com.coinffeine.client.app

import java.util.Currency
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout

import com.coinffeine.client.api.{CoinffeineNetwork, Exchange}
import com.coinffeine.client.api.CoinffeineNetwork._
import com.coinffeine.client.peer.PeerActor
import com.coinffeine.common.currency.{BtcAmount, FiatAmount}
import com.coinffeine.common.protocol.messages.brokerage.{Order, Quote, QuoteRequest}

class DefaultCoinffeineNetwork(peer: ActorRef) extends CoinffeineNetwork {
  implicit private val timeout = Timeout(3.seconds)

  private var _status: CoinffeineNetwork.Status = Disconnected

  override def status = _status

  /** @inheritdoc
    *
    * With the centralized broker implementation over protobuf RPC, "connecting" consists on opening
    * a port with a duplex RPC server.
    */
  override def connect(): Future[Connected.type] = {
    _status = Connecting
    val bindResult = (peer ? PeerActor.Connect).flatMap {
      case PeerActor.Connected => Future.successful(Connected)
      case PeerActor.ConnectionFailed(cause) => Future.failed(ConnectException(cause))
    }
    bindResult.onComplete {
      case Success(connected) => _status = connected
      case Failure(_) => _status = Disconnected
    }
    bindResult
  }

  override def disconnect(): Future[Disconnected.type] = ???

  override def currentQuote(paymentProcessorId: String, currency: Currency): Future[Quote] =
    (peer ? QuoteRequest(currency)).mapTo[Quote]

  override def exchanges: Set[Exchange] = Set.empty

  override def onExchangeChanged(listener: ExchangeListener): Unit = ???

  override def orders: Set[Order] = Set.empty

  override def cancelOrder(order: Order): Unit = ???

  override def submitBuyOrder(btcAmount: BtcAmount, paymentProcessorId: String, fiatAmount: FiatAmount) = ???

  override def submitSellOrder(btcAmount: BtcAmount, paymentProcessorId: String, fiatAmount: FiatAmount) = ???
}
