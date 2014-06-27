package com.coinffeine.client.micropayment

import akka.actor.ActorRef

import com.coinffeine.client.exchange.ProtoMicroPaymentChannel
import com.coinffeine.common.FiatCurrency
import com.coinffeine.common.bitcoin.MutableTransaction
import com.coinffeine.common.exchange.{Exchange, Role}
import com.coinffeine.common.exchange.MicroPaymentChannel.{Signatures, Step}
import com.coinffeine.common.protocol.ProtocolConstants

/** A micropayment channel actor is in charge of performing each of the exchange steps by
  * sending/receiving bitcoins and fiat.
  */
object MicroPaymentChannelActor {

  /** Sent to the the actor to start the actual exchange through the micropayment channel. */
  case class StartMicroPaymentChannel[C <: FiatCurrency](
      exchange: Exchange[C],
      role: Role,
      channel: ProtoMicroPaymentChannel[C],
      constants: ProtocolConstants,
      paymentProcessor: ActorRef,
      messageGateway: ActorRef,
      resultListeners: Set[ActorRef]
  )

  /** Sent to the exchange listeners to notify success of the exchange */
  case object ExchangeSuccess

  /** Sent to the exchange listeners to notify of a failure during the exchange */
  case class ExchangeFailure(cause: Throwable, lastOffer: Option[MutableTransaction])

  private[micropayment] case object StepSignatureTimeout

  case class TimeoutException(message: String) extends RuntimeException(message)

  case class InvalidStepSignatures(step: Step, sigs: Signatures, cause: Throwable)
    extends RuntimeException(s"Received an invalid step signature for $step. Received: $sigs", cause)
}
