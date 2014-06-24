package com.coinffeine.common.protocol.messages.handshake

import com.coinffeine.common.bitcoin.TransactionSignature
import com.coinffeine.common.exchange.Exchange
import com.coinffeine.common.protocol.TransactionSignatureUtils
import com.coinffeine.common.protocol.messages.PublicMessage

case class RefundTxSignatureResponse(
  exchangeId: Exchange.Id,
  refundSignature: TransactionSignature
) extends PublicMessage {

  override def equals(that: Any) = that match {
    case rep: RefundTxSignatureResponse => (rep.exchangeId == exchangeId) &&
      TransactionSignatureUtils.equals(rep.refundSignature, refundSignature)
    case _ => false
  }
}
