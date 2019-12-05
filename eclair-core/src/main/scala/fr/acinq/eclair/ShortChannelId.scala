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

package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto.PublicKey

/**
  * A short channel id uniquely identifies a channel by the coordinates of its funding tx output in the blockchain.
  *
  * See BOLT 7: https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  *
  */
case class ShortChannelId(private val id: Long) extends Ordered[ShortChannelId] {

  def toLong: Long = id

  override def toString: String = {
    val TxCoordinates(blockHeight, txIndex, outputIndex) = ShortChannelId.coordinates(this)
    s"${blockHeight}x${txIndex}x${outputIndex}"
  }

  // we use an unsigned long comparison here
  override def compare(that: ShortChannelId): Int = (this.id + Long.MinValue).compareTo(that.id + Long.MinValue)
}

object ShortChannelId {

  def apply(s: String): ShortChannelId = s.split("x").toList match {
    case blockHeight :: txIndex :: outputIndex :: Nil => ShortChannelId(toShortId(blockHeight.toInt, txIndex.toInt, outputIndex.toInt))
    case _ => throw new IllegalArgumentException(s"Invalid short channel id: $s")
  }

  def apply(blockHeight: Int, txIndex: Int, outputIndex: Int): ShortChannelId = ShortChannelId(toShortId(blockHeight, txIndex, outputIndex))

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long = ((blockHeight & 0xFFFFFFL) << 40) | ((txIndex & 0xFFFFFFL) << 16) | (outputIndex & 0xFFFFL)

  def coordinates(shortChannelId: ShortChannelId): TxCoordinates = TxCoordinates(((shortChannelId.id >> 40) & 0xFFFFFF).toInt, ((shortChannelId.id >> 16) & 0xFFFFFF).toInt, (shortChannelId.id & 0xFFFF).toInt)

  /**
    * This is a trick to encode a partial hash of node id in a short channel id.
    * We use a prefix of 0xff to make it easily distinguishable from normal short channel id.
    */
  def peerId(remoteNodeId: PublicKey): ShortChannelId = ShortChannelId(0xff00000000000000L | remoteNodeId.value.takeRight(7).toLong())

  def isPeerId(shortChannelId: ShortChannelId): Boolean = (shortChannelId.id & 0xff00000000000000L) == 0xff00000000000000L
}

case class TxCoordinates(blockHeight: Int, txIndex: Int, outputIndex: Int)