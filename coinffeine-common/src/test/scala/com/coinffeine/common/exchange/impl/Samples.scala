package com.coinffeine.common.exchange.impl

import scala.concurrent.duration._

import com.google.bitcoin.core.ECKey

import com.coinffeine.common.PeerConnection
import com.coinffeine.common.Currency.Implicits._
import com.coinffeine.common.exchange.Exchange
import com.coinffeine.common.network.CoinffeineUnitTestParams

object Samples {
  val exchange = DefaultExchange(
    id = Exchange.Id("id"),
    parameters = Exchange.Parameters(
      bitcoinAmount = 1.BTC,
      fiatAmount = 1000.EUR,
      totalSteps = Exchange.TotalSteps(10),
      lockTime = 10,
      commitmentConfirmations = 1,
      resubmitRefundSignatureTimeout = 10.seconds,
      refundSignatureAbortTimeout = 30.minutes,
      network = CoinffeineUnitTestParams
    ),
    buyer = Exchange.PeerInfo(PeerConnection("buyer"), "buyerAccount", new ECKey()),
    seller = Exchange.PeerInfo(PeerConnection("seller"), "sellerAccount", new ECKey()),
    broker = Exchange.BrokerInfo(PeerConnection("broker"))
  )
}
