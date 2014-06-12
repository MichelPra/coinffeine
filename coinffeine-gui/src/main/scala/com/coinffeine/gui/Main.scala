package com.coinffeine.gui

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage

import com.coinffeine.common.paymentprocessor.okpay.OkPayCredentials
import com.coinffeine.gui.setup.{CredentialsValidator, SetupWizard}
import com.coinffeine.gui.setup.CredentialsValidator.Result

object Main extends JFXApp {
  JFXApp.AUTO_SHOW = false

  val validator = new CredentialsValidator {
    override def apply(credentials: OkPayCredentials): Future[Result] = Future {
      Thread.sleep(2000)
      if (Random.nextBoolean()) CredentialsValidator.ValidCredentials
      else CredentialsValidator.InvalidCredentials("Random failure")
    }
  }
  val setupConfig = new SetupWizard(validator).show()

  stage = new PrimaryStage {
    title = "Coinffeine"
    scene = new MainScene
  }
  stage.show()
}
