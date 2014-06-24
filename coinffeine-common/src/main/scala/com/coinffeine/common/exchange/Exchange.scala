package com.coinffeine.common.exchange

import java.security.SecureRandom

import com.coinffeine.common.exchange.MicroPaymentChannel.{IntermediateStep, FinalStep}

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import com.coinffeine.common._
import com.coinffeine.common.bitcoin._
import com.coinffeine.common.paymentprocessor.PaymentProcessor

case class Exchange[C <: FiatCurrency](
  id: Exchange.Id,
  parameters: Exchange.Parameters[C],
  buyer: Exchange.PeerInfo[KeyPair],
  seller: Exchange.PeerInfo[KeyPair],
  broker: Exchange.BrokerInfo) {

  val amounts: Exchange.Amounts[C] =
    Exchange.Amounts(parameters.bitcoinAmount, parameters.fiatAmount, parameters.breakdown)
}

object Exchange {

  case class Id(value: String) {
    override def toString = s"exchange:$value"
  }

  object Id {
    private val secureGenerator = new Random(new SecureRandom())

    def random() = new Id(value = secureGenerator.nextString(12))
  }

  case class Parameters[C <: FiatCurrency](bitcoinAmount: BitcoinAmount,
                                           fiatAmount: CurrencyAmount[C],
                                           breakdown: Exchange.StepBreakdown,
                                           lockTime: Long,
                                           commitmentConfirmations: Int,
                                           resubmitRefundSignatureTimeout: FiniteDuration,
                                           refundSignatureAbortTimeout: FiniteDuration,
                                           network: Network)

  case class PeerInfo[KeyPair](connection: PeerConnection,
                               paymentProcessorAccount: PaymentProcessor.AccountId,
                               bitcoinKey: KeyPair)

  case class BrokerInfo(connection: PeerConnection)

  /** How the exchange is break down into steps */
  case class StepBreakdown(intermediateSteps: Int) {
    require(intermediateSteps > 0, s"Intermediate steps must be positive ($intermediateSteps given)")
    val totalSteps = intermediateSteps + 1
  }

  case class Amounts[C <: FiatCurrency](bitcoinAmount: BitcoinAmount,
                                        fiatAmount: CurrencyAmount[C],
                                        breakdown: Exchange.StepBreakdown) {
    require(bitcoinAmount.isPositive,
      s"bitcoin amount must be positive ($bitcoinAmount given)")
    require(fiatAmount.isPositive,
      s"fiat amount must be positive ($fiatAmount given)")

    /** Amount of bitcoins to exchange per intermediate step */
    val stepBitcoinAmount: BitcoinAmount = bitcoinAmount / breakdown.intermediateSteps
    /** Amount of fiat to exchange per intermediate step */
    val stepFiatAmount: CurrencyAmount[C] = fiatAmount / breakdown.intermediateSteps

    /** Total amount compromised in multisignature by the buyer */
    val buyerDeposit: BitcoinAmount = stepBitcoinAmount * 2
    /** Amount refundable by the buyer after a lock time */
    val buyerRefund: BitcoinAmount = buyerDeposit - stepBitcoinAmount

    /** Total amount compromised in multisignature by the seller */
    val sellerDeposit: BitcoinAmount = bitcoinAmount + stepBitcoinAmount
    /** Amount refundable by the seller after a lock time */
    val sellerRefund: BitcoinAmount = sellerDeposit - stepBitcoinAmount

  }
}
