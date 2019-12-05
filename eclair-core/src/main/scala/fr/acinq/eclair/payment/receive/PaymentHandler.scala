/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment.receive

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props}
import akka.event.LoggingAdapter
import fr.acinq.eclair.NodeParams

trait ReceiveHandler {
  def handle(implicit ctx: ActorContext, log: LoggingAdapter): Receive
}

/**
 * Generic payment handler that delegates handling of incoming messages to a list of handlers.
 */
class PaymentHandler(nodeParams: NodeParams, commandBuffer: ActorRef) extends Actor with ActorLogging {

  // we do this instead of sending it to ourselves, otherwise there is no guarantee that this would be the first processed message
  private val multipartHandler = new MultiPartHandler(nodeParams, nodeParams.db.payments, commandBuffer)
  private val payToOpenHandler = new PayToOpenHandler(nodeParams, commandBuffer)

  override def receive: Receive = normal(payToOpenHandler.handle(context, log) orElse multipartHandler.handle(context, log))

  def normal(handle: Receive): Receive = handle orElse {
    case handler: ReceiveHandler =>
      log.info(s"registering handler of type=${handler.getClass.getSimpleName}")
      // NB: the last handler that was added will be the first called
      context become normal(handler.handle(context, log) orElse handle)
  }
}

object PaymentHandler {
  def props(nodeParams: NodeParams, commandBuffer: ActorRef): Props = Props(new PaymentHandler(nodeParams, commandBuffer))
}
