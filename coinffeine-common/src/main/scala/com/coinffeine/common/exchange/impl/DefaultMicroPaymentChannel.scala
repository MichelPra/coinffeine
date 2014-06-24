package com.coinffeine.common.exchange.impl

import com.coinffeine.common.FiatCurrency
import com.coinffeine.common.bitcoin.{ImmutableTransaction, TransactionSignature}
import com.coinffeine.common.exchange.{Deposits, Exchange, MicroPaymentChannel, Role}
import com.coinffeine.common.exchange.MicroPaymentChannel.StepSignatures
import com.coinffeine.common.exchange.impl.DefaultMicroPaymentChannel._

private[impl] class DefaultMicroPaymentChannel[C <: FiatCurrency](
    role: Role,
    exchange: Exchange[C],
    override val deposits: Deposits[ImmutableTransaction],
    override val currentStep: Exchange.StepNumber = Exchange.StepNumber.First)
  extends MicroPaymentChannel[C] {

  private val currentUnsignedTransaction = ImmutableTransaction {
    TransactionProcessor.createUnsignedTransaction(
      inputs = Seq(
        deposits.buyerDeposit.get.getOutput(0),
        deposits.sellerDeposit.get.getOutput(0)
      ),
      outputs = Seq(
        exchange.buyer.bitcoinKey -> exchange.amounts.channelOutputForBuyerAfter(currentStep),
        exchange.seller.bitcoinKey -> exchange.amounts.channelOutputForSellerAfter(currentStep)
      ),
      network = exchange.parameters.network
    )
  }

  override def validateCurrentTransactionSignatures(herSignatures: StepSignatures): Boolean = {
    val tx = currentUnsignedTransaction.get

    def isValid(index: Int, signature: TransactionSignature) =
      TransactionProcessor.isValidSignature(tx, index, signature, role.her(exchange).bitcoinKey,
        Seq(exchange.buyer.bitcoinKey, exchange.seller.bitcoinKey))

    isValid(BuyerDepositInputIndex, herSignatures.buyerDepositSignature) &&
      isValid(SellerDepositInputIndex, herSignatures.sellerDepositSignature)
  }

  override def signCurrentTransaction: StepSignatures = ???

  override def nextStep: DefaultMicroPaymentChannel[C] =
    new DefaultMicroPaymentChannel[C](role, exchange, deposits, currentStep.next)

  override def closingTransaction(herSignatures: StepSignatures) = ???
}

private[impl] object DefaultMicroPaymentChannel {
  val BuyerDepositInputIndex = 0
  val SellerDepositInputIndex = 1
}
