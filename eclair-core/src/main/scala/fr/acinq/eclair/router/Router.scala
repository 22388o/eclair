package fr.acinq.eclair.router

import akka.actor.{ActorRef, FSM, Props, Status}
import akka.pattern.pipe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.wire._
import org.jgrapht.WeightedGraph
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph._

import scala.collection.JavaConversions._
import scala.collection.immutable.{SortedMap, TreeMap}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// @formatter:off

case class ChannelDesc(id: Long, a: PublicKey, b: PublicKey)
case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate)
case class RouteRequest(source: PublicKey, target: PublicKey, assistedRoutes: Seq[Seq[ExtraHop]] = Nil, ignoreNodes: Set[PublicKey] = Set.empty, ignoreChannels: Set[Long] = Set.empty)
case class RouteResponse(hops: Seq[Hop], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long]) { require(hops.size > 0, "route cannot be empty") }
case class ExcludeChannel(desc: ChannelDesc) // this is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed)
case class LiftChannelExclusion(desc: ChannelDesc)
case class SendChannelQuery(to: ActorRef)
case object GetRoutingState
case class RoutingState(channels: Iterable[ChannelAnnouncement], updates: Iterable[ChannelUpdate], nodes: Iterable[NodeAnnouncement])
case class Stash(updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])
case class Rebroadcast(channels: Map[ChannelAnnouncement, Set[ActorRef]], updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])

case class DescEdge(desc: ChannelDesc, u: ChannelUpdate) extends DefaultWeightedEdge

case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                  channels: SortedMap[Long, ChannelAnnouncement], // critical for performance that keys are sorted
                  updates: Map[ChannelDesc, ChannelUpdate],
                  stash: Stash,
                  awaiting: Map[ChannelAnnouncement, Seq[ActorRef]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                  privateChannels: Map[Long, PublicKey], // short_channel_id -> node_id
                  privateUpdates: Map[ChannelDesc, ChannelUpdate],
                  excludedChannels: Set[ChannelDesc], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                  graph: DirectedWeightedPseudograph[PublicKey, DescEdge]
               )

sealed trait State
case object NORMAL extends State

case object TickBroadcast
case object TickPruneStaleChannels

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(nodeParams: NodeParams, watcher: ActorRef) extends FSM[State, Data] {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerBroadcastInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour, repeat = true)

  val db = nodeParams.networkDb

  {
    log.info("loading network announcements from db...")
    // On Android, we discard the node announcements
    val channels = db.listChannels()
    val updates = db.listChannelUpdates()
    log.info("loaded from db: channels={} nodes={} updates={}", channels.size, 0, updates.size)

    // this will be used to calculate routes
    val graph = new DirectedWeightedPseudograph[PublicKey, DescEdge](classOf[DescEdge])

    val initChannels = channels.keys.foldLeft(TreeMap.empty[Long, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
    val initChannelUpdates = updates.map { u =>
      val desc = getDesc(u, initChannels(u.shortChannelId))
      addEdge(graph, desc, u)
      (desc) -> u
    }.toMap

    log.info(s"initialization completed, ready to process messages")
    startWith(NORMAL, Data(Map.empty, initChannels, initChannelUpdates, Stash(Map.empty, Map.empty), awaiting = Map.empty, privateChannels = Map.empty, privateUpdates = Map.empty, excludedChannels = Set.empty, graph))
  }

  when(NORMAL) {
    case Event(LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, channelAnnouncement_opt, u), d: Data) =>
      d.channels.get(shortChannelId) match {
        case Some(_) =>
          // channel has already been announced and router knows about it, we can process the channel_update
          stay using handle(u, self, d)
        case None =>
          channelAnnouncement_opt match {
            case Some(c) if d.awaiting.contains(c) =>
              // channel is currently beeing verified, we can process the channel_update right away (it will be stashed)
              stay using handle(u, self, d)
            case Some(c) =>
              // channel wasn't announced but here is the announcement, we will process it *before* the channel_update
              watcher ! ValidateRequest(c)
              val d1 = d.copy(awaiting = d.awaiting + (c -> Nil)) // no origin
              stay using handle(u, self, d1)
            case None if d.privateChannels.contains(shortChannelId) =>
              // channel isn't announced but we already know about it, we can process the channel_update
              stay using handle(u, self, d)
            case None =>
              // channel isn't announced and we never heard of it (maybe it is a private channel or maybe it is a public channel that doesn't yet have 6 confirmations)
              // let's create a corresponding private channel and process the channel_update
              log.info("adding unannounced local channel to remote={} shortChannelId={}", remoteNodeId, shortChannelId.toHexString)
              stay using handle(u, self, d.copy(privateChannels = d.privateChannels + (shortChannelId -> remoteNodeId)))
          }
      }

    case Event(LocalChannelDown(_, channelId, shortChannelId, _), d: Data) =>
      log.debug("removed local channel_update for channelId={} shortChannelId={}", channelId, shortChannelId.toHexString)
      stay using d.copy(privateChannels = d.privateChannels - shortChannelId, privateUpdates = d.privateUpdates.filterKeys(_.id != shortChannelId))

    case Event(c: ChannelAnnouncement, d) =>
      log.debug("received channel announcement for shortChannelId={} nodeId1={} nodeId2={} from {}", c.shortChannelId.toHexString, c.nodeId1, c.nodeId2, sender)
      if (d.channels.contains(c.shortChannelId)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (duplicate)", c)
        stay
      } else if (d.awaiting.contains(c)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (being verified)", c)
        // adding the sender to the list of origins so that we don't send back the same announcement to this peer later
        val origins = d.awaiting(c) :+ sender
        stay using d.copy(awaiting = d.awaiting + (c -> origins))
      } else if (!Announcements.checkSigs(c)) {
        sender ! TransportHandler.ReadAck(c)
        log.warning("bad signature for announcement {}", c)
        sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        stay
      } else {
        sender ! TransportHandler.ReadAck(c)
        // On Android, we don't validate announcements for now, it means that neither awaiting nor stashed announcements are used
        db.addChannel(c, BinaryData(""), Satoshi(0))
        stay using d.copy(
          channels = d.channels + (c.shortChannelId -> c),
          privateChannels = d.privateChannels - c.shortChannelId // we remove fake announcements that we may have made before)
        )
      }

    case Event(n: NodeAnnouncement, d: Data) =>
      sender ! TransportHandler.ReadAck(n)
      stay // we just ignore node_announcements on Android

    case Event(u: ChannelUpdate, d: Data) =>
      sender ! TransportHandler.ReadAck(u)
      log.debug("received channel update for shortChannelId={} from {}", u.shortChannelId.toHexString, sender)
      stay using handle(u, sender, d)

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)), d)
      if d.channels.contains(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId)
      log.info("funding tx of channelId={} has been spent", shortChannelId.toHexString)
      // we need to remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels - lostChannel.shortChannelId
      val lostNodes = Seq(lostChannel.nodeId1, lostChannel.nodeId2).filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      log.info("pruning shortChannelId={} (spent)", shortChannelId.toHexString)
      db.removeChannel(shortChannelId) // NB: this also removes channel updates
      // we also need to remove updates from the graph
      removeEdge(d.graph, ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId1, lostChannel.nodeId2))
      removeEdge(d.graph, ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId2, lostChannel.nodeId1))
      context.system.eventStream.publish(ChannelLost(shortChannelId))
      lostNodes.foreach {
        case nodeId =>
          log.info("pruning nodeId={} (spent)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, updates = d.updates.filterKeys(_.id != shortChannelId))

    case Event(TickBroadcast, d) =>
      // On Android we don't rebroadcast announcements
      stay

    case Event(TickPruneStaleChannels, d) =>
      // first we select channels that we will prune
      val staleChannels = getStaleChannels(d.channels.values, d.updates)
      // then we clean up the related channel updates
      val staleUpdates = staleChannels.map(d.channels).flatMap(c => Seq(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2), ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1)))
      // finally we remove nodes that aren't tied to any channels anymore (and deduplicate them)
      val potentialStaleNodes = staleChannels.map(d.channels).flatMap(c => Set(c.nodeId1, c.nodeId2)).toSet
      val channels1 = d.channels -- staleChannels
      // no need to iterate on all nodes, just on those that are affected by current pruning
      val staleNodes = potentialStaleNodes.filterNot(nodeId => hasChannels(nodeId, channels1.values))

      // let's clean the db and send the events
      staleChannels.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId.toHexString)
        db.removeChannel(shortChannelId) // NB: this also removes channel updates
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      // we also need to remove updates from the graph
      staleChannels.map(d.channels).foreach { c =>
        removeEdge(d.graph, ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2))
        removeEdge(d.graph, ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1))
      }
      staleNodes.foreach {
        case nodeId =>
          log.info("pruning nodeId={} (stale)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- staleNodes, channels = channels1, updates = d.updates -- staleUpdates)

    case Event(ExcludeChannel(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      val banDuration = nodeParams.channelExcludeDuration
      log.info("excluding shortChannelId={} from nodeId={} for duration={}", shortChannelId.toHexString, nodeId, banDuration)
      context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
      stay using d.copy(excludedChannels = d.excludedChannels + desc)

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info("reinstating shortChannelId={} from nodeId={}", shortChannelId.toHexString, nodeId)
      stay using d.copy(excludedChannels = d.excludedChannels - desc)

    case Event('nodes, d) =>
      sender ! d.nodes.values
      stay

    case Event('channels, d) =>
      sender ! d.channels.values
      stay

    case Event('updates, d) =>
      sender ! (d.updates ++ d.privateUpdates).values
      stay

    case Event('updatesMap, d) =>
      sender ! (d.updates ++ d.privateUpdates)
      stay

    case Event('dot, d) =>
      graph2dot(d.nodes, d.channels) pipeTo sender
      stay

    case Event(RouteRequest(start, end, assistedRoutes, ignoreNodes, ignoreChannels), d) =>
      // we convert extra routing info provided in the payment request to fake channel_update
      // it takes precedence over all other channel_updates we know
      val assistedUpdates = assistedRoutes.flatMap(toFakeUpdates(_, end)).toMap
      // we also filter out updates corresponding to channels/nodes that are blacklisted for this particular request
      val blacklisted = getBlacklistedChannels(d.channels, d.updates ++ assistedUpdates, ignoreNodes, ignoreChannels) ++ d.excludedChannels
      log.info(s"finding a route $start->$end with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedUpdates.keys.map(_.id.toHexString).mkString(","), ignoreNodes.map(_.toBin).mkString(","), ignoreChannels.map(_.toHexString).mkString(","), d.excludedChannels.map(_.id.toHexString).mkString(","))
      findRoute(d.graph, start, end, withEdges = assistedUpdates, withoutEdges = blacklisted)
        .map(r => sender ! RouteResponse(r, ignoreNodes, ignoreChannels))
        .recover { case t => sender ! Status.Failure(t) }
      // On Android, we don't monitor channels to see if their funding is spent because it is too expensive
      // if the node that created this channel tells us it is unusable (only permanent channel failure) we forget about it
      // note that if the channel is in fact still alive, we will get it again via network announcements anyway
      ignoreChannels.foreach(shortChannelId => self ! WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)))
      stay

    case Event(GetRoutingState, d: Data) =>
      stay // ignored on Android

    case Event(SendChannelQuery(remote), _) =>
      // ask for everything
      val query = QueryChannelRange(nodeParams.chainHash, firstBlockNum = 0, numberOfBlocks = Int.MaxValue)
      log.debug("querying channel range {}", query)
      remote ! query
      stay

    case Event(query@QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks), d) =>
      sender ! TransportHandler.ReadAck(query)
      // On Android we ignore queries
      stay

    case Event(reply@ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, data), d) =>
      sender ! TransportHandler.ReadAck(reply)
      if (chainHash != nodeParams.chainHash) {
        log.warning("received reply_channel_range message for chain {}, we're on {}", chainHash, nodeParams.chainHash)
      } else {
        val theirShortChannelIds = Announcements.unzip(data)
        val ourShortChannelIds = d.channels.keys.filter(keep(firstBlockNum, numberOfBlocks, _, d.channels, d.updates)) // note: order is preserved
        val missing = theirShortChannelIds -- ourShortChannelIds
        log.info("we received their reply, we're missing {} channel announcements/updates", missing.size)
        sender ! QueryShortChannelId(chainHash, Announcements.zip(missing))
      }
      stay

    case Event(query@QueryShortChannelId(chainHash, data), d) =>
      sender ! TransportHandler.ReadAck(query)
      // On Android we ignore queries
      stay
  }

  initialize()

  def handle(n: NodeAnnouncement, origin: ActorRef, d: Data): Data =
    if (d.stash.nodes.contains(n)) {
      log.debug("ignoring {} (already stashed)", n)
      val origins = d.stash.nodes(n) + origin
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> origins)))
    } else if (d.nodes.contains(n.nodeId) && d.nodes(n.nodeId).timestamp >= n.timestamp) {
      log.debug("ignoring {} (old timestamp or duplicate)", n)
      d
    } else if (!Announcements.checkSig(n)) {
      log.warning("bad signature for {}", n)
      origin ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n))
    } else if (d.channels.values.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("added node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeDiscovered(n))
      db.addNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n))
    } else if (d.awaiting.keys.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("stashing {}", n)
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> Set(origin))))
    } else {
      log.debug("ignoring {} (no related channel found)", n)
      // there may be a record if we have just restarted
      db.removeNode(n.nodeId)
      d
    }

  def handle(u: ChannelUpdate, origin: ActorRef, d: Data): Data =
    if (d.channels.contains(u.shortChannelId)) {
      // related channel is already known (note: this means no related channel_update is in the stash)
      val publicChannel = true
      val c = d.channels(u.shortChannelId)
      val desc = getDesc(u, c)
      if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (old timestamp or duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId.toHexString, u)
        origin ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        d
      } else if (d.updates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        db.updateChannelUpdate(u)
        // we also need to update the graph
        removeEdge(d.graph, desc)
        addEdge(d.graph, desc, u)
        d.copy(updates = d.updates + (desc -> u))
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        db.addChannelUpdate(u)
        // we also need to update the graph
        addEdge(d.graph, desc, u)
        d.copy(updates = d.updates + (desc -> u), privateUpdates = d.privateUpdates - desc)
      }
    } else if (d.awaiting.keys.exists(c => c.shortChannelId == u.shortChannelId)) {
      // channel is currently being validated
      if (d.stash.updates.contains(u)) {
        log.debug("ignoring {} (already stashed)", u)
        val origins = d.stash.updates(u) + origin
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins)))
      } else {
        log.debug("stashing {}", u)
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> Set(origin))))
      }
    } else if (d.privateChannels.contains(u.shortChannelId)) {
      val publicChannel = false
      val remoteNodeId = d.privateChannels(u.shortChannelId)
      val (a, b) = if (Announcements.isNode1(nodeParams.nodeId, remoteNodeId)) (nodeParams.nodeId, remoteNodeId) else (remoteNodeId, nodeParams.nodeId)
      val desc = if (Announcements.isNode1(u.flags)) ChannelDesc(u.shortChannelId, a, b) else ChannelDesc(u.shortChannelId, b, a)
      if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (old timestamp or duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId.toHexString, u)
        origin ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        d
      } else if (d.privateUpdates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        // we also need to update the graph
        removeEdge(d.graph, desc)
        addEdge(d.graph, desc, u)
        d.copy(privateUpdates = d.privateUpdates + (desc -> u))
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        // we also need to update the graph
        addEdge(d.graph, desc, u)
        d.copy(privateUpdates = d.privateUpdates + (desc -> u))
      }
    } else {
      log.debug("ignoring announcement {} (unknown channel)", u)
      d
    }

}

object Router {

  def props(nodeParams: NodeParams, watcher: ActorRef) = Props(new Router(nodeParams, watcher))

  def toFakeUpdate(extraHop: ExtraHop): ChannelUpdate =
  // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
  // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = "", chainHash = "", extraHop.shortChannelId, Platform.currentTime / 1000, flags = BinaryData("0000"), extraHop.cltvExpiryDelta, htlcMinimumMsat = 0L, extraHop.feeBaseMsat, extraHop.feeProportionalMillionths)

  def toFakeUpdates(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Map[ChannelDesc, ChannelUpdate] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map {
      case (extraHop: ExtraHop, nextNodeId) => (ChannelDesc(extraHop.shortChannelId, extraHop.nodeId, nextNodeId) -> toFakeUpdate(extraHop))
    }.toMap
  }

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    require(u.flags.data.size == 2, s"invalid flags length ${u.flags.data.size} != 2")
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.flags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[ChannelAnnouncement]): Boolean = channels.exists(c => isRelatedTo(c, nodeId))

  /**
    * Is valid (not stale) a channel that:
    * (1) is younger than 2 weeks (2*7*144 = 2016 blocks)
    * OR
    * (2) has at least one channel_update younger than 2 weeks
    * @param channel
    * @param update1
    * @param update2
    */
  def isValid(channel: ChannelAnnouncement, update1: Option[ChannelUpdate], update2: Option[ChannelUpdate]) = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdBlocks = Globals.blockCount.get() - 2016
    val staleThresholdSeconds = Platform.currentTime / 1000 - 1209600
    val (blockHeight, _, _) = fromShortId(channel.shortChannelId)
    blockHeight >= staleThresholdBlocks || update1.map(_.timestamp).getOrElse(0L) > staleThresholdSeconds || update2.map(_.timestamp).getOrElse(0L) > staleThresholdSeconds
  }

  /**
    * filter channel for advanced sync
    */
  def keep(firstBlockNum: Int, numberOfBlocks: Int, id: Long, channels: Map[Long, ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate]): Boolean = {
    val (height, _, _) = fromShortId(id)
    val c = channels(id)
    val u1 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2))
    val u2 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1))
    height >= firstBlockNum && height <= (firstBlockNum + numberOfBlocks) && isValid(c, u1, u2)
  }


  /**
    * Is stale a channel that:
    * (1) is older than 2 weeks (2*7*144 = 2016 blocks)
    * AND
    * (2) has no channel_update younger than 2 weeks
    *
    * @param channels
    * @param updates
    * @return
    */
  def getStaleChannels(channels: Iterable[ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate]): Iterable[Long] = {
    val staleChannels = channels.filter { c =>
      val update1 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2))
      val update2 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1))
      !isValid(c, update1, update2)
    }
    staleChannels.map(_.shortChannelId)
  }

  /**
    * Filters announcements that we want to send to nodes asking an `initial_routing_sync`
    *
    * @param channels
    * @param nodes
    * @param updates
    * @return
    */
  def getValidAnnouncements(channels: Map[Long, ChannelAnnouncement], nodes: Map[PublicKey, NodeAnnouncement], updates: Map[ChannelDesc, ChannelUpdate]): (Iterable[ChannelAnnouncement], Iterable[NodeAnnouncement], Iterable[ChannelUpdate]) = {
    val staleChannels = getStaleChannels(channels.values, updates)
    val validChannels = (channels -- staleChannels).values
    val staleUpdates = staleChannels.map(channels).flatMap(c => Seq(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2), ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1)))
    val validUpdates = (updates -- staleUpdates).values
    val validNodes = validChannels.flatMap(c => nodes.get(c.nodeId1) ++ nodes.get(c.nodeId2)).toSet
    (validChannels, validNodes, validUpdates)
  }

  /**
    * This method is used after a payment failed, and we want to exclude some nodes/channels that we know are failing
    */
  def getBlacklistedChannels(channels: Map[Long, ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long]): Iterable[ChannelDesc] = {
    val descNode = if (ignoreNodes.isEmpty) {
      Iterable.empty[ChannelDesc]
    } else {
      // expensive, but node blacklisting shouldn't happen often
      updates.keys.filter(desc => !ignoreNodes.contains(desc.a) && !ignoreNodes.contains(desc.b))
    }
    val descChannel = ignoreChannels.map(channels).flatMap { c => Vector(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2), ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1)) }
    descNode ++ descChannel
  }

  /**
    * Routing fee have a variable part, as a simplification we compute fees using a default constant value for the amount
    */
  val DEFAULT_AMOUNT_MSAT = 10000000

  /**
    * Careful: this function *mutates* the graph
    *
    * Note that we only add the edge if the corresponding channel is enabled
    */
  def addEdge(g: WeightedGraph[PublicKey, DescEdge], d: ChannelDesc, u: ChannelUpdate) = {
    if (Announcements.isEnabled(u.flags)) {
      g.addVertex(d.a)
      g.addVertex(d.b)
      val e = new DescEdge(d, u)
      val weight = nodeFee(u.feeBaseMsat, u.feeProportionalMillionths, DEFAULT_AMOUNT_MSAT).toDouble
      g.addEdge(d.a, d.b, e)
      g.setEdgeWeight(e, weight)
    }
  }

  /**
    * Careful: this function *mutates* the graph
    *
    * NB: we don't clean up vertices
    *
    */
  def removeEdge(g: WeightedGraph[PublicKey, DescEdge], d: ChannelDesc) = {
    import scala.collection.JavaConversions._
    Option(g.getAllEdges(d.a, d.b)) match {
      case Some(edges) => edges.find(_.desc == d) match {
        case Some(e) => g.removeEdge(e)
        case None => ()
      }
      case None => ()
    }
  }

  /**
    * Find a route in the graph between localNodeId and targetNodeId
    *
    * @param g
    * @param localNodeId
    * @param targetNodeId
    * @param withEdges    those will be added before computing the route, and removed after so that g is left unchanged
    * @param withoutEdges those will be removed before computing the route, and added back after so that g is left unchanged
    * @return
    */
  def findRoute(g: DirectedWeightedPseudograph[PublicKey, DescEdge], localNodeId: PublicKey, targetNodeId: PublicKey, withEdges: Map[ChannelDesc, ChannelUpdate] = Map.empty, withoutEdges: Iterable[ChannelDesc] = Iterable.empty): Try[Seq[Hop]] = Try {
    if (localNodeId == targetNodeId) throw CannotRouteToSelf
    val workingGraph = if (withEdges.isEmpty && withoutEdges.isEmpty) {
      // no filtering, let's work on the base graph
      g
    } else {
      // slower but safer: we duplicate the graph and add/remove updates from the duplicated version
      val clonedGraph = g.clone().asInstanceOf[DirectedWeightedPseudograph[PublicKey, DescEdge]]
      withEdges.foreach { case (d, u) =>
        removeEdge(clonedGraph, d)
        addEdge(clonedGraph, d, u)
      }
      withoutEdges.foreach { d => removeEdge(clonedGraph, d) }
      clonedGraph
    }
    if (!workingGraph.containsVertex(localNodeId)) throw RouteNotFound
    if (!workingGraph.containsVertex(targetNodeId)) throw RouteNotFound
    val route_opt = Option(DijkstraShortestPath.findPathBetween(workingGraph, localNodeId, targetNodeId))
    route_opt match {
      case Some(path) => path.map(edge => Hop(edge.desc.a, edge.desc.b, edge.u))
      case None => throw RouteNotFound
    }
  }

  def graph2dot(nodes: Map[PublicKey, NodeAnnouncement], channels: Map[Long, ChannelAnnouncement])(implicit ec: ExecutionContext): Future[String] = ???


}
