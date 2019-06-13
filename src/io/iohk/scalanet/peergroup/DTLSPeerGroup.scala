package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.cert.Certificate
import java.security.{PrivateKey, PublicKey}
import java.util.concurrent.ConcurrentHashMap

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.peergroup.DTLSPeerGroup.Config
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.subjects.{PublishSubject, ReplaySubject, Subject}
import org.eclipse.californium.elements._
import org.eclipse.californium.scandium.DTLSConnector
import org.eclipse.californium.scandium.config.DtlsConnectorConfig
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite._

import scala.collection.JavaConverters._

class DTLSPeerGroup[M](val config: Config)(
    implicit codec: Codec[M],
    bufferInstantiator: BufferInstantiator[ByteBuffer],
    scheduler: Scheduler
) extends PeerGroup[InetMultiAddress, M] {

  private val serverConnector = createServerConnector()

  private val channelSubject = PublishSubject[Channel[InetMultiAddress, M]]()

  private val activeChannels = new ConcurrentHashMap[(InetSocketAddress, InetSocketAddress), ChannelImpl]().asScala

  override def processAddress: InetMultiAddress = config.processAddress

  override def initialize(): Task[Unit] = {
    Task(serverConnector.start())
  }

  override def client(to: InetMultiAddress): Task[Channel[InetMultiAddress, M]] = Task {
    val connector = createClientConnector()
    connector.start()
    val id = (connector.getAddress, to.inetSocketAddress)
    assert(!activeChannels.contains(id), s"HOUSTON, WE HAVE A MULTIPLEXING PROBLEM")
    val channel = new ClientChannelImpl(to, connector)
    activeChannels.put(id, channel)
    channel
  }

  override def server(): Observable[Channel[InetMultiAddress, M]] = channelSubject

  override def shutdown(): Task[Unit] =
    for {
      _ <- Task(serverConnector.stop())
      _ <- Task(serverConnector.destroy())
    } yield ()

  private class ChannelImpl(val to: InetMultiAddress, dtlsConnector: DTLSConnector)(implicit codec: Codec[M])
      extends Channel[InetMultiAddress, M] {

    override val in: Subject[M, M] = ReplaySubject[M]()

    override def sendMessage(message: M): Task[Unit] = {
      import io.iohk.scalanet.peergroup.BufferConversionOps._
      val buffer = codec.encode(message)

      Task.async[Unit] { (_, c) =>
        val messageCallback = new MessageCallback {
          override def onConnecting(): Unit = ()
          override def onDtlsRetransmission(i: Int): Unit = ()
          override def onContextEstablished(endpointContext: EndpointContext): Unit = ()

          override def onSent(): Unit = c.onSuccess(())
          override def onError(throwable: Throwable): Unit = c.onError(throwable)
        }

        val rawData =
          RawData.outbound(buffer.toArray, new AddressEndpointContext(to.inetSocketAddress), messageCallback, false)

        dtlsConnector.send(rawData)

        Cancelable.empty
      }
    }

    override def close(): Task[Unit] = {
      val id = (dtlsConnector.getAddress, to.inetSocketAddress)
      activeChannels(id).in.onComplete()
      activeChannels.remove(id)
      Task.unit
    }
  }

  private class ClientChannelImpl(to: InetMultiAddress, dtlsConnector: DTLSConnector)(implicit codec: Codec[M])
      extends ChannelImpl(to, dtlsConnector) {
    override def close(): Task[Unit] = {
      dtlsConnector.stop()
      dtlsConnector.destroy()
      super.close()
    }
  }

  private def createClientConnector(): DTLSConnector = {
    val connectorConfig = config.scandiumConfigBuilder
      .setAddress(new InetSocketAddress(config.processAddress.inetSocketAddress.getAddress, 0))
      .setClientOnly()
      .build()

    val connector = new DTLSConnector(connectorConfig)

    connector.setRawDataReceiver(new RawDataChannel {
      override def receiveData(rawData: RawData): Unit = {
        val channelId = (connector.getAddress, rawData.getInetSocketAddress)

        assert(activeChannels.contains(channelId), s"Missing channel for channelId $channelId")

        val activeChannel: ChannelImpl = activeChannels(channelId)

        val messageE = codec.decode(ByteBuffer.wrap(rawData.bytes))

        messageE.foreach(message => activeChannel.in.onNext(message))
      }
    })

    connector
  }

  private def createServerConnector(): DTLSConnector = {
    val connectorConfig = config.scandiumConfigBuilder.build()

    val connector = new DTLSConnector(connectorConfig)

    connector.setRawDataReceiver(new RawDataChannel {
      override def receiveData(rawData: RawData): Unit = {
        val channelId = (processAddress.inetSocketAddress, rawData.getInetSocketAddress)

        val activeChannel: ChannelImpl = activeChannels.getOrElseUpdate(channelId, createNewChannel(rawData))

        val messageE = codec.decode(ByteBuffer.wrap(rawData.bytes))

        messageE.foreach(message => activeChannel.in.onNext(message))
      }

      private def createNewChannel(rawData: RawData): ChannelImpl = {
        val newChannel = new ChannelImpl(InetMultiAddress(rawData.getInetSocketAddress), connector)
        channelSubject.onNext(newChannel)
        newChannel
      }
    })

    connector
  }
}

object DTLSPeerGroup {

  val supportedCipherSuites = Seq(
    TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
    TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
    TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8,
    TLS_ECDHE_ECDSA_WITH_AES_128_CCM,
    TLS_ECDHE_ECDSA_WITH_AES_256_CCM,
    TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
    TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
    TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384
  )

  trait Config {
    val bindAddress: InetSocketAddress
    val processAddress: InetMultiAddress
    private[scalanet] def scandiumConfigBuilder: DtlsConnectorConfig.Builder
  }

  object Config {

    case class Unauthenticated(
        bindAddress: InetSocketAddress,
        processAddress: InetMultiAddress,
        publicKey: PublicKey,
        privateKey: PrivateKey
    ) extends Config {
      override def scandiumConfigBuilder: DtlsConnectorConfig.Builder =
        new DtlsConnectorConfig.Builder()
          .setAddress(bindAddress)
          .setSupportedCipherSuites(supportedCipherSuites: _*)
          .setIdentity(privateKey, publicKey)
          .setRpkTrustAll()
    }

    object Unauthenticated {
      def apply(
          bindAddress: InetSocketAddress,
          publicKey: PublicKey,
          privateKey: PrivateKey
      ): Unauthenticated =
        Unauthenticated(
          bindAddress,
          InetMultiAddress(bindAddress),
          publicKey,
          privateKey
        )
    }

    /*
    certificate_list
      This is a sequence (chain) of certificates.  The sender's
      certificate MUST come first in the list.  Each following
      certificate MUST directly certify the one preceding it.  Because
      certificate validation requires that root keys be distributed
      independently, the self-signed certificate that specifies the root
      certificate authority MAY be omitted from the chain, under the
      assumption that the remote end must already possess it in order to
      validate it in any case.
     */
    case class CertAuthenticated(
        bindAddress: InetSocketAddress,
        processAddress: InetMultiAddress,
        certificateChain: Array[Certificate],
        privateKey: PrivateKey,
        trustedCerts: Array[Certificate]
    ) extends Config {
      override def scandiumConfigBuilder: DtlsConnectorConfig.Builder = {
        new DtlsConnectorConfig.Builder()
          .setAddress(bindAddress)
          .setSupportedCipherSuites(supportedCipherSuites: _*)
          .setIdentity(privateKey, certificateChain)
          .setTrustStore(trustedCerts)
      }
    }

    object CertAuthenticated {
      def apply(
          bindAddress: InetSocketAddress,
          certificateChain: Array[Certificate],
          privateKey: PrivateKey,
          trustedCerts: Array[Certificate]
      ): CertAuthenticated =
        CertAuthenticated(bindAddress, InetMultiAddress(bindAddress), certificateChain, privateKey, trustedCerts)
    }
  }
}