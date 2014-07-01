package com.coinffeine.common.exchange

import scala.util.{Success, Try}

import com.coinffeine.common._
import com.coinffeine.common.bitcoin._
import com.coinffeine.common.exchange.Exchange.Deposits
import com.coinffeine.common.network.CoinffeineUnitTestNetwork

class MockExchangeProtocol extends ExchangeProtocol {

  override def createHandshake(
      exchange: Exchange[_ <: FiatCurrency],
      role: Role,
      unspentOutputs: Seq[UnspentOutput],
      changeAddress: Address) = new MockHandshake(exchange, role)

  override def createMicroPaymentChannel(
      exchange: Exchange[_ <: FiatCurrency], role: Role, deposits: Exchange.Deposits) =
    new MockMicroPaymentChannel(exchange)

  override def validateDeposits(transactions: Both[ImmutableTransaction],
                                exchange: Exchange[_ <: FiatCurrency]): Try[Deposits] =
    Success(MockExchangeProtocol.DummyDeposits)

  override def validateDeposit(role: Role, transaction: ImmutableTransaction,
                               exchange: Exchange[_ <: FiatCurrency]): Try[Unit] = Success {}
}

object MockExchangeProtocol {
  private val DummyDeposit = ImmutableTransaction(new MutableTransaction(CoinffeineUnitTestNetwork))
  val DummyDeposits = Exchange.Deposits(Both(DummyDeposit, DummyDeposit))
}
