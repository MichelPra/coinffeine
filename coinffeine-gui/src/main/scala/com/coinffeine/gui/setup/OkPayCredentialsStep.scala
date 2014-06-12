package com.coinffeine.gui.setup

import scalafx.beans.property.ObjectProperty
import scalafx.scene.control.Label
import scalafx.scene.layout.StackPane

import com.coinffeine.gui.wizard.Step

/** TODO: write a real implementation for this wizard step */
private[setup] class OkPayCredentialsStep extends Step[SetupConfig] {

  override val pane = new StackPane() {
    content = new Label("OKPay credentials")
  }

  override def bindTo(data: ObjectProperty[SetupConfig]): Unit = {}
}
