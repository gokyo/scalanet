package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs._
import io.iohk.scalanet.discovery.ethereum.v4.mocks.{MockSigAlg, MockPeerGroup, MockChannel}
import io.iohk.scalanet.discovery.ethereum.v4.Payload.Ping
import io.iohk.scalanet.discovery.ethereum.v4.Payload._
import io.iohk.scalanet.peergroup.Channel.ChannelEvent
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import io.iohk.scalanet.NetUtils.aRandomAddress
import java.net.InetSocketAddress
import monix.execution.Scheduler
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import scala.util.Random
import scodec.bits.BitVector

class DiscoveryNetworkSpec extends FlatSpec with Matchers {
  import DiscoveryNetworkSpec._

  def test(fixture: Fixture) = {
    fixture.test.runSyncUnsafe(5.seconds)
  }

  behavior of "ping"

  it should "send an unexpired correctly versioned Ping Packet with the the local and remote addresses" in test {
    new Fixture {
      val remoteENRSeq = 123L

      override val test = for {
        _ <- network.ping(remoteAddress)(None)
        _ <- network.ping(remoteAddress)(Some(remoteENRSeq))
        now = System.currentTimeMillis

        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        msg1 <- channel.nextMessageFromSUT()
        msg2 <- channel.nextMessageFromSUT()
      } yield {
        channel.isClosed shouldBe true

        assertMessageFrom(publicKey, msg1) {
          case Ping(version, from, to, expiration, enrSeq) =>
            version shouldBe 4
            from shouldBe toNodeAddress(peerGroup.processAddress)
            to shouldBe toNodeAddress(remoteAddress)
            assertExpirationSet(now, expiration)
            enrSeq shouldBe empty
        }

        assertMessageFrom(publicKey, msg2) {
          case Ping(_, _, _, _, enrSeq) =>
            enrSeq shouldBe Some(remoteENRSeq)
        }
      }
    }
  }

  it should "return None if the peer times out" in test {
    new Fixture {
      override val test = for {
        result <- network.ping(remoteAddress)(None)
      } yield {
        result shouldBe empty
      }
    }
  }

  it should "return Some ENRSEQ if the peer responds" in test {
    new Fixture {
      val remoteENRSeq = 123L

      override val requestTimeout = 1.second

      override val test = for {
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        pinging <- network.ping(remoteAddress)(None).start

        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          Pong(
            to = toNodeAddress(remoteAddress),
            pingHash = packet.hash,
            expiration = System.currentTimeMillis + messageExpiration.toMillis,
            enrSeq = Some(remoteENRSeq)
          ),
          remotePrivateKey
        )

        maybeRemoteENRSeq <- pinging.join
      } yield {
        maybeRemoteENRSeq shouldBe Some(Some(remoteENRSeq))
      }
    }
  }

  it should "return None if the Pong hash doesn't match the Ping" in test {
    new Fixture {
      override val requestTimeout = 1.second

      override val test = for {
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        pinging <- network.ping(remoteAddress)(None).start

        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          Pong(
            toNodeAddress(remoteAddress),
            pingHash = packet.hash.reverse,
            expiration = System.currentTimeMillis + messageExpiration.toMillis,
            enrSeq = None
          ),
          remotePrivateKey
        )

        maybeRemoteENRSeq <- pinging.join
      } yield {
        maybeRemoteENRSeq shouldBe empty
      }
    }
  }

  it should "return None if the Pong is expired" in test {
    new Fixture {
      override val requestTimeout = 1.second

      override val test = for {
        channel <- peerGroup.getOrCreateChannel(remoteAddress)
        pinging <- network.ping(remoteAddress)(None).start

        msg <- channel.nextMessageFromSUT()
        packet = assertPacketReceived(msg)
        _ <- channel.sendPayloadToSUT(
          Pong(
            toNodeAddress(remoteAddress),
            pingHash = packet.hash,
            expiration = System.currentTimeMillis - messageExpiration.toMillis * 2,
            enrSeq = None
          ),
          remotePrivateKey
        )

        maybeRemoteENRSeq <- pinging.join
      } yield {
        maybeRemoteENRSeq shouldBe empty
      }
    }
  }

  behavior of "findNode"
  it should "send an unexpired FindNode Packet with the given target" in (pending)
  it should "return None if the peer times out" in (pending)
  it should "return Some Nodes if the peer responds" in (pending)
  it should "collect responses up to the timeout" in (pending)
  it should "collect responses up to the bucket size" in (pending)

  behavior of "enrRequest"
  it should "send an unexpired ENRRequest Packet" in (pending)
  it should "return None if the peer times out" in (pending)
  it should "return Some ENR if the peer responds" in (pending)

  behavior of "startHandling"
  it should "start handling requests in the background" in (pending)
  it should "handle multiple channels in parallel" in (pending)
  it should "stop handling when canceled" in (pending)
  it should "close idle channels" in (pending)
  it should "ignore incoming response messages" in (pending)
  it should "not respond to expired Ping" in (pending)
  it should "not respond with a Pong if the handler returns None" in (pending)
  it should "respond with an unexpired Pong with the correct hash if the handler returns Some ENRSEQ" in (pending)
  it should "not respond to expired FindNode" in (pending)
  it should "not respond with Neighbors if the handler returns None" in (pending)
  it should "respond with multiple unexpired Neighbors each within the packet size limit if the handler returns Some Nodes" in (pending)
  it should "not respond to expired ENRRequest" in (pending)
  it should "not respond with ENRResponse if the handler returns None" in (pending)
  it should "respond with an ENRResponse with the correct hash if the handler returns Some ENR" in (pending)

  behavior of "getMaxNeighborsPerPacket"

  it should "correctly estimate the maximum number" in {
    val maxNeighborsPerPacket = DiscoveryNetwork.getMaxNeighborsPerPacket
    // We're using scodec encoding here, so it's not exactly the same as RLP,
    // but it should be less than the default Kademlia bucket size of 16.
    maxNeighborsPerPacket should be > 1
    maxNeighborsPerPacket should be < 16
  }
}

object DiscoveryNetworkSpec extends Matchers {
  implicit val scheduler: Scheduler = Scheduler.Implicits.global
  implicit val sigalg: SigAlg = new MockSigAlg

  def randomBytes(n: Int) = {
    val bytes = Array.ofDim[Byte](n)
    Random.nextBytes(bytes)
    bytes
  }

  def randomKeyPair: (PublicKey, PrivateKey) = {
    // Using mock keys with the MockSigAlg, it returns the private as public.
    assert(sigalg.PrivateKeyBytesSize == sigalg.PublicKeyBytesSize)
    val privateKey = PrivateKey(BitVector(randomBytes(sigalg.PrivateKeyBytesSize)))
    val publicKey = PublicKey(privateKey)
    publicKey -> privateKey
  }

  def toNodeAddress(address: InetSocketAddress): Node.Address =
    Node.Address(
      ip = BitVector(address.getHostName.getBytes),
      udpPort = address.getPort,
      tcpPort = address.getPort
    )

  trait Fixture {
    // Implement `test` to assert something.
    def test: Task[_]

    val requestTimeout = 100.millis
    val messageExpiration = 60.seconds
    val kademliaTimeout = 250.millis
    val kademliaBucketSize = 16

    // Keys for the System Under Test.
    lazy val (publicKey, privateKey) = randomKeyPair

    // A random peer to talk to.
    lazy val remoteAddress = aRandomAddress
    lazy val (remotePublicKey, remotePrivateKey) = randomKeyPair

    lazy val peerGroup: MockPeerGroup[InetSocketAddress, Packet] =
      new MockPeerGroup(
        processAddress = aRandomAddress
      )

    lazy val network: DiscoveryNetwork[InetSocketAddress] =
      DiscoveryNetwork[InetSocketAddress](
        peerGroup = peerGroup,
        privateKey = privateKey,
        toNodeAddress = toNodeAddress,
        messageExpiration = messageExpiration,
        requestTimeout = requestTimeout,
        kademliaTimeout = kademliaTimeout,
        kademliaBucketSize = kademliaBucketSize
      ).runSyncUnsafe()

    def assertExpirationSet(now: Long, expiration: Long) =
      expiration shouldBe (now + messageExpiration.toMillis) +- 1000

    implicit class ChannelOps(channel: MockChannel[InetSocketAddress, Packet]) {
      def sendPayloadToSUT(
          payload: Payload,
          privateKey: PrivateKey
      ): Task[Unit] = {
        channel.sendMessageToSUT(Packet.pack(payload, privateKey).require)
      }
    }
  }

  def assertPacketReceived(maybeEvent: Option[ChannelEvent[Packet]]): Packet = {
    maybeEvent match {
      case Some(event) =>
        event match {
          case MessageReceived(packet) =>
            packet
          case other =>
            fail(s"Expected MessageReceived; got $other")
        }

      case None =>
        fail("Channel event was empty.")
    }
  }

  def assertMessageFrom[T](publicKey: PublicKey, maybeEvent: Option[ChannelEvent[Packet]])(
      pf: PartialFunction[Payload, T]
  ): T = {
    val packet = assertPacketReceived(maybeEvent)
    val (payload, remotePublicKey) =
      Packet.unpack(packet).require

    remotePublicKey shouldBe publicKey

    if (pf.isDefinedAt(payload))
      pf(payload)
    else
      fail(s"Unexpected payload: $payload")
  }
}
