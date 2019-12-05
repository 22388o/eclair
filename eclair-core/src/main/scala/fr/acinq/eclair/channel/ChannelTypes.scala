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

package fr.acinq.eclair.channel

import java.util.UUID

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, DeterministicWallet, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.wire.{AcceptChannel, ChannelAnnouncement, ChannelReestablish, ChannelUpdate, ClosingSigned, FailureMessage, FundingCreated, FundingLocked, FundingSigned, Init, OnionRoutingPacket, OpenChannel, Shutdown, UpdateAddHtlc}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, ShortChannelId, UInt64}
import scodec.bits.{BitVector, ByteVector}

/**
 * Created by PM on 20/05/2016.
 */

// @formatter:off

/*
       .d8888b. 88888888888     d8888 88888888888 8888888888 .d8888b.
      d88P  Y88b    888        d88888     888     888       d88P  Y88b
      Y88b.         888       d88P888     888     888       Y88b.
       "Y888b.      888      d88P 888     888     8888888    "Y888b.
          "Y88b.    888     d88P  888     888     888           "Y88b.
            "888    888    d88P   888     888     888             "888
      Y88b  d88P    888   d8888888888     888     888       Y88b  d88P
       "Y8888P"     888  d88P     888     888     8888888888 "Y8888P"
 */
sealed trait State
case object WAIT_FOR_INIT_INTERNAL extends State
case object WAIT_FOR_OPEN_CHANNEL extends State
case object WAIT_FOR_ACCEPT_CHANNEL extends State
case object WAIT_FOR_FUNDING_INTERNAL extends State
case object WAIT_FOR_FUNDING_CREATED extends State
case object WAIT_FOR_FUNDING_SIGNED extends State
case object WAIT_FOR_FUNDING_CONFIRMED extends State
case object WAIT_FOR_FUNDING_LOCKED extends State
case object NORMAL extends State
case object SHUTDOWN extends State
case object NEGOTIATING extends State
case object CLOSING extends State
case object CLOSED extends State
case object OFFLINE extends State
case object SYNCING extends State
case object WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT extends State
case object ERR_FUNDING_LOST extends State
case object ERR_INFORMATION_LEAK extends State

/*
      8888888888 888     888 8888888888 888b    888 88888888888 .d8888b.
      888        888     888 888        8888b   888     888    d88P  Y88b
      888        888     888 888        88888b  888     888    Y88b.
      8888888    Y88b   d88P 8888888    888Y88b 888     888     "Y888b.
      888         Y88b d88P  888        888 Y88b888     888        "Y88b.
      888          Y88o88P   888        888  Y88888     888          "888
      888           Y888P    888        888   Y8888     888    Y88b  d88P
      8888888888     Y8P     8888888888 888    Y888     888     "Y8888P"
 */

case class INPUT_INIT_FUNDER(temporaryChannelId: ByteVector32, fundingAmount: Satoshi, pushAmount: MilliSatoshi, initialFeeratePerKw: Long, fundingTxFeeratePerKw: Long, localParams: LocalParams, remote: ActorRef, remoteInit: Init, channelFlags: Byte, channelVersion: ChannelVersion)
case class INPUT_INIT_FUNDEE(temporaryChannelId: ByteVector32, localParams: LocalParams, remote: ActorRef, remoteInit: Init)
case object INPUT_CLOSE_COMPLETE_TIMEOUT // when requesting a mutual close, we wait for as much as this timeout, then unilateral close
case object INPUT_DISCONNECTED
case class INPUT_RECONNECTED(remote: ActorRef, localInit: Init, remoteInit: Init)
case class INPUT_RESTORED(data: HasCommitments)

sealed trait BitcoinEvent
case object BITCOIN_FUNDING_PUBLISH_FAILED extends BitcoinEvent
case object BITCOIN_FUNDING_DEPTHOK extends BitcoinEvent
case object BITCOIN_FUNDING_DEEPLYBURIED extends BitcoinEvent
case object BITCOIN_FUNDING_LOST extends BitcoinEvent
case object BITCOIN_FUNDING_TIMEOUT extends BitcoinEvent
case object BITCOIN_FUNDING_SPENT extends BitcoinEvent
case object BITCOIN_OUTPUT_SPENT extends BitcoinEvent
case class BITCOIN_TX_CONFIRMED(tx: Transaction) extends BitcoinEvent
case class BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId: ShortChannelId) extends BitcoinEvent
case class BITCOIN_PARENT_TX_CONFIRMED(childTx: Transaction) extends BitcoinEvent

/*
       .d8888b.   .d88888b.  888b     d888 888b     d888        d8888 888b    888 8888888b.   .d8888b.
      d88P  Y88b d88P" "Y88b 8888b   d8888 8888b   d8888       d88888 8888b   888 888  "Y88b d88P  Y88b
      888    888 888     888 88888b.d88888 88888b.d88888      d88P888 88888b  888 888    888 Y88b.
      888        888     888 888Y88888P888 888Y88888P888     d88P 888 888Y88b 888 888    888  "Y888b.
      888        888     888 888 Y888P 888 888 Y888P 888    d88P  888 888 Y88b888 888    888     "Y88b.
      888    888 888     888 888  Y8P  888 888  Y8P  888   d88P   888 888  Y88888 888    888       "888
      Y88b  d88P Y88b. .d88P 888   "   888 888   "   888  d8888888888 888   Y8888 888  .d88P Y88b  d88P
       "Y8888P"   "Y88888P"  888       888 888       888 d88P     888 888    Y888 8888888P"   "Y8888P"
 */

sealed trait Upstream
object Upstream {
  /** Our node is the origin of the payment. */
  final case class Local(id: UUID) extends Upstream
  /** Our node forwarded a single incoming HTLC to an outgoing channel. */
  final case class Relayed(add: UpdateAddHtlc) extends Upstream
  /** Our node forwarded an incoming HTLC set to a remote outgoing node (potentially producing multiple downstream HTLCs). */
  final case class TrampolineRelayed(adds: Seq[UpdateAddHtlc]) extends Upstream {
    val amountIn: MilliSatoshi = adds.map(_.amountMsat).sum
    val expiryIn: CltvExpiry = adds.map(_.cltvExpiry).min
  }
}

sealed trait Command
final case class CMD_ADD_HTLC(amount: MilliSatoshi, paymentHash: ByteVector32, cltvExpiry: CltvExpiry, onion: OnionRoutingPacket, upstream: Upstream, commit: Boolean = false, previousFailures: Seq[AddHtlcFailed] = Seq.empty) extends Command
final case class CMD_FULFILL_HTLC(id: Long, r: ByteVector32, commit: Boolean = false) extends Command
final case class CMD_FAIL_HTLC(id: Long, reason: Either[ByteVector, FailureMessage], commit: Boolean = false) extends Command
final case class CMD_FAIL_MALFORMED_HTLC(id: Long, onionHash: ByteVector32, failureCode: Int, commit: Boolean = false) extends Command
final case class CMD_UPDATE_FEE(feeratePerKw: Long, commit: Boolean = false) extends Command
final case object CMD_SIGN extends Command
final case class CMD_CLOSE(scriptPubKey: Option[ByteVector]) extends Command
final case class CMD_UPDATE_RELAY_FEE(feeBase: MilliSatoshi, feeProportionalMillionths: Long) extends Command
final case object CMD_FORCECLOSE extends Command
final case object CMD_GETSTATE extends Command
final case object CMD_GETSTATEDATA extends Command
final case object CMD_GETINFO extends Command
final case class RES_GETINFO(nodeId: PublicKey, channelId: ByteVector32, state: State, data: Data)

/*
      8888888b.        d8888 88888888888     d8888
      888  "Y88b      d88888     888        d88888
      888    888     d88P888     888       d88P888
      888    888    d88P 888     888      d88P 888
      888    888   d88P  888     888     d88P  888
      888    888  d88P   888     888    d88P   888
      888  .d88P d8888888888     888   d8888888888
      8888888P" d88P     888     888  d88P     888
 */

sealed trait Data

case object Nothing extends Data

sealed trait HasCommitments extends Data {
  def commitments: Commitments
  def channelId = commitments.channelId
}

case class ClosingTxProposed(unsignedTx: Transaction, localClosingSigned: ClosingSigned)

case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], claimHtlcDelayedTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32])
case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: List[Transaction], claimHtlcTimeoutTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32])
case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPenaltyTx: Option[Transaction], htlcPenaltyTxs: List[Transaction], claimHtlcDelayedPenaltyTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32])

final case class DATA_WAIT_FOR_OPEN_CHANNEL(initFundee: INPUT_INIT_FUNDEE) extends Data
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder: INPUT_INIT_FUNDER, lastSent: OpenChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId: ByteVector32, localParams: LocalParams, remoteParams: RemoteParams, fundingAmount: Satoshi, pushAmount: MilliSatoshi, initialFeeratePerKw: Long, remoteFirstPerCommitmentPoint: PublicKey, channelVersion: ChannelVersion, lastSent: OpenChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId: ByteVector32, localParams: LocalParams, remoteParams: RemoteParams, fundingAmount: Satoshi, pushAmount: MilliSatoshi, initialFeeratePerKw: Long, remoteFirstPerCommitmentPoint: PublicKey, channelFlags: Byte, channelVersion: ChannelVersion, lastSent: AcceptChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_SIGNED(channelId: ByteVector32, localParams: LocalParams, remoteParams: RemoteParams, fundingTx: Transaction, fundingTxFee: Satoshi, localSpec: CommitmentSpec, localCommitTx: CommitTx, remoteCommit: RemoteCommit, channelFlags: Byte, channelVersion: ChannelVersion, lastSent: FundingCreated) extends Data
final case class DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments: Commitments,
                                                 fundingTx: Option[Transaction],
                                                 waitingSince: Long, // how long have we been waiting for the funding tx to confirm
                                                 deferred: Option[FundingLocked],
                                                 lastSent: Either[FundingCreated, FundingSigned]) extends Data with HasCommitments
final case class DATA_WAIT_FOR_FUNDING_LOCKED(commitments: Commitments, shortChannelId: ShortChannelId, lastSent: FundingLocked) extends Data with HasCommitments
final case class DATA_NORMAL(commitments: Commitments,
                             shortChannelId: ShortChannelId,
                             buried: Boolean,
                             channelAnnouncement: Option[ChannelAnnouncement],
                             channelUpdate: ChannelUpdate,
                             localShutdown: Option[Shutdown],
                             remoteShutdown: Option[Shutdown]) extends Data with HasCommitments
final case class DATA_SHUTDOWN(commitments: Commitments,
                               localShutdown: Shutdown, remoteShutdown: Shutdown) extends Data with HasCommitments
final case class DATA_NEGOTIATING(commitments: Commitments,
                                  localShutdown: Shutdown, remoteShutdown: Shutdown,
                                  closingTxProposed: List[List[ClosingTxProposed]], // one list for every negotiation (there can be several in case of disconnection)
                                  bestUnpublishedClosingTx_opt: Option[Transaction]) extends Data with HasCommitments {
  require(closingTxProposed.nonEmpty, "there must always be a list for the current negotiation")
  require(!commitments.localParams.isFunder || closingTxProposed.forall(_.nonEmpty), "funder must have at least one closing signature for every negotation attempt because it initiates the closing")
}
final case class DATA_CLOSING(commitments: Commitments,
                              fundingTx: Option[Transaction], // this will be non-empty if we are funder and we got in closing while waiting for our own tx to be published
                              waitingSince: Long, // how long since we initiated the closing
                              mutualCloseProposed: List[Transaction], // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
                              mutualClosePublished: List[Transaction] = Nil,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              nextRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              futureRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: List[RevokedCommitPublished] = Nil) extends Data with HasCommitments {
  val spendingTxes = mutualClosePublished ::: localCommitPublished.map(_.commitTx).toList ::: remoteCommitPublished.map(_.commitTx).toList ::: nextRemoteCommitPublished.map(_.commitTx).toList ::: futureRemoteCommitPublished.map(_.commitTx).toList ::: revokedCommitPublished.map(_.commitTx)
  require(spendingTxes.nonEmpty, "there must be at least one tx published in this state")
}

final case class DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(commitments: Commitments, remoteChannelReestablish: ChannelReestablish) extends Data with HasCommitments

final case class LocalParams(nodeId: PublicKey,
                             fundingKeyPath: DeterministicWallet.KeyPath,
                             dustLimit: Satoshi,
                             maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                             channelReserve: Satoshi,
                             htlcMinimum: MilliSatoshi,
                             toSelfDelay: CltvExpiryDelta,
                             maxAcceptedHtlcs: Int,
                             isFunder: Boolean,
                             defaultFinalScriptPubKey: ByteVector,
                             globalFeatures: ByteVector,
                             localFeatures: ByteVector)

final case class RemoteParams(nodeId: PublicKey,
                              dustLimit: Satoshi,
                              maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                              channelReserve: Satoshi,
                              htlcMinimum: MilliSatoshi,
                              toSelfDelay: CltvExpiryDelta,
                              maxAcceptedHtlcs: Int,
                              fundingPubKey: PublicKey,
                              revocationBasepoint: PublicKey,
                              paymentBasepoint: PublicKey,
                              delayedPaymentBasepoint: PublicKey,
                              htlcBasepoint: PublicKey,
                              globalFeatures: ByteVector,
                              localFeatures: ByteVector)

object ChannelFlags {
  val AnnounceChannel = 0x01.toByte
  val Empty = 0x00.toByte
}

case class ChannelVersion(bits: BitVector) {
  require(bits.size == ChannelVersion.LENGTH_BITS, "channel version takes 4 bytes")

  def |(other: ChannelVersion) = ChannelVersion(bits | other.bits)
  def &(other: ChannelVersion) = ChannelVersion(bits & other.bits)
  def ^(other: ChannelVersion) = ChannelVersion(bits ^ other.bits)
  def isSet(bit: Int) = bits.reverse.get(bit)
}

object ChannelVersion {
  import scodec.bits._
  val LENGTH_BITS = 4 * 8
  val ZEROES = ChannelVersion(bin"00000000000000000000000000000000")
  val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0
  val ZERO_RESERVE_BIT = 3

  def fromBit(bit: Int) = ChannelVersion(BitVector.low(LENGTH_BITS).set(bit).reverse)

  val USE_PUBKEY_KEYPATH = fromBit(USE_PUBKEY_KEYPATH_BIT)
  val ZERO_RESERVE = fromBit(ZERO_RESERVE_BIT)

  val STANDARD = ZEROES | USE_PUBKEY_KEYPATH
}
// @formatter:on
