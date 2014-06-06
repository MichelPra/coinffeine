package com.coinffeine.client.peer.orders

import scala.concurrent.duration._

import akka.actor.Props

import com.coinffeine.common.{AkkaSpec, PeerConnection}
import com.coinffeine.common.currency.CurrencyCode.{EUR, USD}
import com.coinffeine.common.currency.Implicits._
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.GatewayProbe
import com.coinffeine.common.protocol.messages.brokerage._

class OrdersActorTest extends AkkaSpec {

  val constants = ProtocolConstants.DefaultConstants.copy(
    orderExpirationInterval = 6.seconds,
    orderResubmitInterval = 4.seconds
  )
  val broker = PeerConnection("broker")
  val eurOrder1 = Order(Bid, 1.3.BTC, 556.EUR)
  val eurOrder2 = Order(Ask, 0.7.BTC, 640.EUR)
  val firstEurOrder = OrderSet(Market(EUR.currency), bids = Seq(OrderSet.Entry(1.3.BTC, 556.EUR)))
  val bothEurOrders = firstEurOrder.addOrder(Ask, 0.7.BTC, 640.EUR)

  trait Fixture {
    val gateway = new GatewayProbe()
    val actor = system.actorOf(Props(new OrdersActor(constants)))
    actor ! OrdersActor.Initialize(gateway.ref, broker)
  }

  "An order submission actor" must "keep silent as long as there is no open orders" in new Fixture {
    gateway.expectNoMsg()
  }

  it must "submit all orders as soon as a new one is open" in new Fixture {
    actor ! eurOrder1
    gateway.expectForwarding(firstEurOrder, broker)
    actor ! eurOrder2
    gateway.expectForwarding(bothEurOrders, broker)
  }

  it must "keep resubmitting open orders to avoid them being discarded" in new Fixture {
    actor ! eurOrder1
    gateway.expectForwarding(firstEurOrder, broker)
    gateway.expectForwarding(firstEurOrder, broker, timeout = constants.orderExpirationInterval)
    gateway.expectForwarding(firstEurOrder, broker, timeout = constants.orderExpirationInterval)
  }

  it must "group orders by target market" in new Fixture {
    actor ! eurOrder1
    actor ! Order(Ask, 0.5.BTC, 500.USD)
    gateway.expectForwardingPF(broker, constants.orderExpirationInterval) {
      case OrderSet(Market(EUR.currency), _, _) =>
    }
    gateway.expectForwardingPF(broker, constants.orderExpirationInterval) {
      case OrderSet(Market(USD.currency), _, _) =>
    }
  }
}
