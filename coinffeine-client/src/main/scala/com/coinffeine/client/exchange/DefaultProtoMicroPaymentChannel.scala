package com.coinffeine.client.exchange

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration._
import scala.util.Try

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout

import com.coinffeine.client.{ExchangeInfo, MultiSigInfo}
import com.coinffeine.common.{BitcoinAmount, Currency, FiatCurrency}
import com.coinffeine.common.bitcoin._
import com.coinffeine.common.exchange.MicroPaymentChannel.StepSignatures
import com.coinffeine.common.exchange.impl.TransactionProcessor
import com.coinffeine.common.paymentprocessor.{Payment, PaymentProcessor}

class DefaultProtoMicroPaymentChannel[C <: FiatCurrency](
    override val exchangeInfo: ExchangeInfo[C],
    paymentProcessor: ActorRef,
    sellerCommitmentTx: ImmutableTransaction,
    buyerCommitmentTx: ImmutableTransaction) extends ProtoMicroPaymentChannel[C] {
  this: UserRole =>

  import com.coinffeine.client.exchange.DefaultProtoMicroPaymentChannel._

  private implicit val paymentProcessorTimeout = Timeout(5.seconds)
  private val sellerFunds = sellerCommitmentTx.get.getOutput(0)
  private val buyerFunds = buyerCommitmentTx.get.getOutput(0)
  requireValidSellerFunds(sellerFunds)
  requireValidBuyerFunds(buyerFunds)

  private def requireValidFunds(funds: MutableTransactionOutput): Unit = {
    require(funds.getScriptPubKey.isSentToMultiSig,
      "Transaction with funds is invalid because is not sending the funds to a multisig")
    val multisigInfo = MultiSigInfo(funds.getScriptPubKey)
    require(multisigInfo.requiredKeyCount == 2,
      "Funds are sent to a multisig that do not require 2 keys")
    require(multisigInfo.possibleKeys == Set(exchangeInfo.user.bitcoinKey, exchangeInfo.counterpart.bitcoinKey),
      "Possible keys in multisig script does not match the expected keys")
  }

  private def requireValidBuyerFunds(buyerFunds: MutableTransactionOutput): Unit = {
    requireValidFunds(buyerFunds)
    require(Currency.Bitcoin.fromSatoshi(buyerFunds.getValue) == exchangeInfo.btcStepAmount * 2,
      "The amount of committed funds by the buyer does not match the expected amount")
  }

  private def requireValidSellerFunds(sellerFunds: MutableTransactionOutput): Unit = {
    requireValidFunds(sellerFunds)
    require(
      Currency.Bitcoin.fromSatoshi(sellerFunds.getValue) ==
        exchangeInfo.parameters.bitcoinAmount + exchangeInfo.btcStepAmount,
      "The amount of committed funds by the seller does not match the expected amount")
  }

  override def pay(step: Int): Future[Payment[C]] = for {
    paid <- paymentProcessor.ask(PaymentProcessor.Pay(
      exchangeInfo.counterpart.paymentProcessorAccount,
      exchangeInfo.fiatStepAmount,
      getPaymentDescription(step))).mapTo[PaymentProcessor.Paid[C]]
  } yield paid.payment

  override def getOffer(step: Int): MutableTransaction = getOffer(
    buyerAmount = exchangeInfo.btcStepAmount * step,
    sellerAmount = exchangeInfo.parameters.bitcoinAmount - exchangeInfo.btcStepAmount * step
  )

  override def finalOffer: MutableTransaction = getOffer(
    buyerAmount = exchangeInfo.parameters.bitcoinAmount + exchangeInfo.btcStepAmount * 2,
    sellerAmount = exchangeInfo.btcStepAmount
  )

  private def getOffer(buyerAmount: BitcoinAmount, sellerAmount: BitcoinAmount): MutableTransaction =
    TransactionProcessor.createUnsignedTransaction(
      inputs = Seq(buyerFunds, sellerFunds),
      outputs = Seq(buyersKey -> buyerAmount, sellersKey -> sellerAmount),
      network = exchangeInfo.parameters.network
    )

  override def validateSellersSignature(
      step: Int,
      signature0: TransactionSignature,
      signature1: TransactionSignature): Try[Unit] =
    validateSellersSignature(
      getOffer(step),
      signature0,
      signature1,
      s"The provided signature is invalid for the offer in step $step")

  override def validatePayment(step: Int, paymentId: String): Future[Unit] = {
    for {
      found <- paymentProcessor.ask(
        PaymentProcessor.FindPayment(paymentId)).mapTo[PaymentProcessor.PaymentFound[_]]
    } yield {
      val payment = found.payment
      require(payment.amount == exchangeInfo.fiatStepAmount,
        "Payment amount does not match expected amount")
      require(payment.receiverId == sellersFiatAddress,
        "Payment is not being sent to the seller")
      require(payment.senderId == buyersFiatAddress,
        "Payment is not coming from the buyer")
      require(payment.description == getPaymentDescription(step),
        "Payment does not have the required description")
      ()
    }
  }

  override def validateSellersFinalSignature(
      signature0: TransactionSignature, signature1: TransactionSignature): Try[Unit] =
    validateSellersSignature(
      finalOffer,
      signature0,
      signature1,
      s"The provided signature is invalid for the final offer")

  private val requiredSignatures = Seq(
    exchangeInfo.exchange.buyer.bitcoinKey,
    exchangeInfo.exchange.seller.bitcoinKey
  )

  override protected def sign(offer: MutableTransaction) = StepSignatures(
    buyerDepositSignature = TransactionProcessor.signMultiSignedOutput(
      offer, 0, exchangeInfo.user.bitcoinKey, requiredSignatures),
    sellerDepositSignature = TransactionProcessor.signMultiSignedOutput(
      offer, 1, exchangeInfo.user.bitcoinKey, requiredSignatures)
  )

  private def getPaymentDescription(step: Int) = s"Payment for ${exchangeInfo.id}, step $step"

  private def validateSellersSignature(
      tx: MutableTransaction,
      signature0: TransactionSignature,
      signature1: TransactionSignature,
      validationErrorMessage: String): Try[Unit] = Try {
    validateSellersSignature(tx, 0, signature0, validationErrorMessage)
    validateSellersSignature(tx, 1, signature1, validationErrorMessage)
  }

  private def validateSellersSignature(
      tx: MutableTransaction,
      inputIndex: Int,
      signature: TransactionSignature,
      validationErrorMessage: String): Unit = {
    require(
      TransactionProcessor.isValidSignature(tx, inputIndex, signature, sellersKey, requiredSignatures),
      s"Invalid signature for input $inputIndex: $validationErrorMessage"
    )
  }

  /** Returns a signed transaction ready to be broadcast */
  override def getSignedOffer(step: Int, herSignatures: StepSignatures) = {
    val tx = getOffer(step)
    val signatures = Seq(sign(tx), herSignatures)
    TransactionProcessor.setMultipleSignatures(tx, BuyerDepositInputIndex, signatures.map(_.buyerDepositSignature): _*)
    TransactionProcessor.setMultipleSignatures(tx, SellerDepositInputIndex, signatures.map(_.sellerDepositSignature): _*)
    tx
  }
}

object DefaultProtoMicroPaymentChannel {
  val BuyerDepositInputIndex = 0
  val SellerDepositInputIndex = 1
}
