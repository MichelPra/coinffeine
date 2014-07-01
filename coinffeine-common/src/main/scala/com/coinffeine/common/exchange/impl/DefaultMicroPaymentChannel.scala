package com.coinffeine.common.exchange.impl

import scala.util.Try
import scala.util.control.NonFatal

import com.coinffeine.common.bitcoin.{ImmutableTransaction, TransactionSignature}
import com.coinffeine.common.exchange._
import com.coinffeine.common.exchange.MicroPaymentChannel._
import com.coinffeine.common.exchange.impl.DefaultMicroPaymentChannel._

private[impl] class DefaultMicroPaymentChannel private (
    exchange: AnyOngoingExchange,
    deposits: Exchange.Deposits,
    override val currentStep: Step) extends MicroPaymentChannel {

  def this(exchange: AnyOngoingExchange, deposits: Exchange.Deposits) =
    this(exchange, deposits, IntermediateStep(1, exchange.parameters.breakdown))

  private val currentUnsignedTransaction = ImmutableTransaction {
    import exchange.amounts._

    val split = if (currentStep.isFinal) Both(
      buyer = buyerDeposit + bitcoinAmount,
      seller = sellerDeposit - bitcoinAmount
    ) else Both(
      buyer = stepBitcoinAmount * (currentStep.value - 1),
      seller = bitcoinAmount - stepBitcoinAmount * (currentStep.value - 1)
    )

    TransactionProcessor.createUnsignedTransaction(
      inputs = deposits.transactions.toSeq.map(_.get.getOutput(0)),
      outputs = Seq(
        exchange.participants.buyer.bitcoinKey -> split.buyer,
        exchange.participants.seller.bitcoinKey -> split.seller
      ),
      network = exchange.parameters.network
    )
  }

  override def validateCurrentTransactionSignatures(herSignatures: Signatures): Try[Unit] = {
    val tx = currentUnsignedTransaction.get
    val herKey = exchange.participants(exchange.role.counterpart).bitcoinKey

    def requireValidSignature(index: Int, signature: TransactionSignature) = {
      require(
        TransactionProcessor.isValidSignature(tx, index, signature, herKey, exchange.requiredSignatures),
        s"Signature $signature cannot satisfy ${tx.getInput(index)}"
      )
    }

    Try {
      requireValidSignature(BuyerDepositInputIndex, herSignatures.buyer)
      requireValidSignature(SellerDepositInputIndex, herSignatures.seller)
    } recover {
      case NonFatal(cause) => throw InvalidSignaturesException(herSignatures, cause)
    }
  }

  override def signCurrentTransaction = {
    val tx = currentUnsignedTransaction.get
    val signingKey = exchange.participants(exchange.role).bitcoinKey
    Signatures(
      buyer = TransactionProcessor.signMultiSignedOutput(
        tx, BuyerDepositInputIndex, signingKey, exchange.requiredSignatures),
      seller = TransactionProcessor.signMultiSignedOutput(
        tx, SellerDepositInputIndex, signingKey, exchange.requiredSignatures)
    )
  }

  override def nextStep = new DefaultMicroPaymentChannel(exchange, deposits, currentStep.next)

  override def closingTransaction(herSignatures: Signatures) = {
    validateCurrentTransactionSignatures(herSignatures).get
    val tx = currentUnsignedTransaction.get
    val signatures = Seq(signCurrentTransaction, herSignatures)
    val buyerSignatures = signatures.map(_.buyer)
    val sellerSignatures = signatures.map(_.seller)
    TransactionProcessor.setMultipleSignatures(tx, BuyerDepositInputIndex, buyerSignatures: _*)
    TransactionProcessor.setMultipleSignatures(tx, SellerDepositInputIndex, sellerSignatures: _*)
    ImmutableTransaction(tx)
  }
}

private[impl] object DefaultMicroPaymentChannel {
  val BuyerDepositInputIndex = 0
  val SellerDepositInputIndex = 1
}
