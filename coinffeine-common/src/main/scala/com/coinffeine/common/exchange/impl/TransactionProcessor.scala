package com.coinffeine.common.exchange.impl

import scala.collection.JavaConversions._

import com.google.bitcoin.core._
import com.google.bitcoin.core.Transaction.SigHash
import com.google.bitcoin.crypto.TransactionSignature
import com.google.bitcoin.script.ScriptBuilder

import com.coinffeine.common.{BitcoinAmount, Currency}
import com.coinffeine.common.Currency.Implicits._

/** This trait encapsulates the transaction processing actions. */
object TransactionProcessor {

  @deprecated
  def createMultiSignedDeposit(userWallet: Wallet,
                               amountToCommit: BitcoinAmount,
                               requiredSignatures: Seq[ECKey],
                               network: NetworkParameters): Transaction = {
    require(amountToCommit.isPositive, "Amount to commit must be greater than zero")

    val inputFunds = collectFunds(userWallet, amountToCommit)
    val totalInputFunds = valueOf(inputFunds)
    require(totalInputFunds >= amountToCommit,
      "Input funds must cover the amount of funds to commit")

    val tx = new Transaction(network)
    inputFunds.foreach(tx.addInput)
    addMultisignOutput(tx, amountToCommit, requiredSignatures)
    addChangeOutput(tx, totalInputFunds, amountToCommit, userWallet.getChangeAddress)
    tx.signInputs(SigHash.ALL, userWallet)
    tx
  }

  def createMultiSignedDeposit(unspentOutputs: Seq[(TransactionOutput, ECKey)],
                               amountToCommit: BitcoinAmount,
                               changeAddress: Address,
                               requiredSignatures: Seq[ECKey],
                               network: NetworkParameters): Transaction = {
    require(amountToCommit.isPositive, "Amount to commit must be greater than zero")

    val inputFunds = unspentOutputs.map(_._1)
    val totalInputFunds = valueOf(inputFunds)
    require(totalInputFunds >= amountToCommit,
      "Input funds must cover the amount of funds to commit")

    val tx = new Transaction(network)
    inputFunds.foreach(tx.addInput)
    addMultisignOutput(tx, amountToCommit, requiredSignatures)
    addChangeOutput(tx, totalInputFunds, amountToCommit, changeAddress)

    for (((output, signingKey), index) <- unspentOutputs.zipWithIndex) {
      val input = tx.getInput(index)
      val connectedScript = input.getOutpoint.getConnectedOutput.getScriptBytes
      val signature = tx.calculateSignature(index, signingKey, null, connectedScript, SigHash.ALL, false)
      input.setScriptSig(ScriptBuilder.createInputScript(signature, signingKey))
    }
    tx
  }

  def collectFunds(userWallet: Wallet, amount: BitcoinAmount): Set[TransactionOutput] = {
    val inputFundCandidates = userWallet.calculateAllSpendCandidates(true)
    val necessaryInputCount = inputFundCandidates.view
      .scanLeft(Currency.Bitcoin.Zero)((accum, output) =>
      accum + Currency.Bitcoin.fromSatoshi(output.getValue))
      .takeWhile(_ < amount)
      .length
    inputFundCandidates.take(necessaryInputCount).toSet
  }

  private def addChangeOutput(tx: Transaction, inputAmount: BitcoinAmount,
                              spentAmount: BitcoinAmount, changeAddress: Address): Unit = {
    val changeAmount = inputAmount - spentAmount
    require(!changeAmount.isNegative)
    if (changeAmount.isPositive) {
      tx.addOutput((inputAmount - spentAmount).asSatoshi, changeAddress)
    }
  }

  private def addMultisignOutput(tx: Transaction, amount: BitcoinAmount,
                                 requiredSignatures: Seq[ECKey]): Unit = {
    require(requiredSignatures.size > 1, "should have at least two signatures")
    tx.addOutput(
      amount.asSatoshi,
      ScriptBuilder.createMultiSigOutputScript(requiredSignatures.size, requiredSignatures)
    )
  }

  def createUnsignedTransaction(inputs: Seq[TransactionOutput],
                                outputs: Seq[(ECKey, BitcoinAmount)],
                                network: NetworkParameters,
                                lockTime: Option[Long] = None): Transaction = {
    val tx = new Transaction(network)
    lockTime.foreach(tx.setLockTime)
    for (input <- inputs) { tx.addInput(input).setSequenceNumber(0) }
    for ((pubKey, amount) <- outputs) {
      tx.addOutput(amount.asSatoshi, pubKey)
    }
    tx
  }

  def signMultiSignedOutput(multiSignedDeposit: Transaction, index: Int,
                            signAs: ECKey, requiredSignatures: Seq[ECKey]): TransactionSignature = {
    val script = ScriptBuilder.createMultiSigOutputScript(requiredSignatures.size, requiredSignatures)
    multiSignedDeposit.calculateSignature(index, signAs, script, SigHash.ALL, false)
  }

  def setMultipleSignatures(tx: Transaction,
                            index: Int,
                            signatures: TransactionSignature*): Unit = {
    tx.getInput(index).setScriptSig(ScriptBuilder.createMultiSigInputScript(signatures))
  }

  def isValidSignature(transaction: Transaction,
                       index: Int,
                       signature: TransactionSignature,
                       signerKey: ECKey,
                       requiredSignatures: Seq[ECKey]): Boolean = {
    val input = transaction.getInput(index)
    val script = ScriptBuilder.createMultiSigOutputScript(requiredSignatures.size, requiredSignatures)
    val hash = transaction.hashForSignature(index, script, SigHash.ALL, false)
    signerKey.verify(hash, signature)
  }

  def valueOf(outputs: Traversable[TransactionOutput]): BitcoinAmount =
    outputs.map(funds => Currency.Bitcoin.fromSatoshi(funds.getValue)).reduce(_ + _)
}
