package io.iohk.scalanet.peergroup

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

import io.iohk.decco.auto._
import io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel._
import monix.eval.Task
import UDPPeerGroup._
import io.iohk.decco.{Codec, PartialCodec, TypeCode}
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

class UDPPeerGroup(val config: Config)(implicit scheduler: Scheduler) extends TerminalPeerGroup[InetSocketAddress]() {

  private val log = LoggerFactory.getLogger(getClass)

  private val workerGroup = new NioEventLoopGroup()

  /**
    * 64 kilobytes is the theoretical maximum size of a complete IP datagram
    * https://stackoverflow.com/questions/9203403/java-datagrampacket-udp-maximum-send-recv-buffer-size
    */
  private val server = new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, new DefaultMaxBytesRecvByteBufAllocator)
    .handler(new ChannelInitializer[NioDatagramChannel]() {
      override def initChannel(ch: NioDatagramChannel): Unit = {
        ch.pipeline.addLast(new ServerInboundHandler)
      }
    })
    .bind(config.bindAddress)
    .syncUninterruptibly()
  log.info(s"Server bound to address ${config.bindAddress}")

  override def sendMessage[MessageType](address: InetSocketAddress, message: MessageType)(
      implicit codec: Codec[MessageType]
  ): Task[Unit] = {
    val pdu = PDU(processAddress, message)
    val pduCodec = derivePduCodec[MessageType]
    Task(writeUdp(address, pduCodec.encode(pdu)))
  }

  override def shutdown(): Task[Unit] = {
    Task(server.channel().close().await())
  }

  private class ServerInboundHandler extends ChannelInboundHandlerAdapter {
    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val b = msg.asInstanceOf[DatagramPacket]
      val remoteAddress: InetSocketAddress = b.sender()
      subscribers.notify((remoteAddress, b.content().nioBuffer().asReadOnlyBuffer()))
    }
  }

  private def writeUdp(address: InetSocketAddress, data: ByteBuffer): Unit = {
    val udp = DatagramChannel.open().setOption[Integer](StandardSocketOptions.SO_SNDBUF, 65536)
    udp.configureBlocking(true)
    udp.connect(address)
    try {
      udp.write(data)
    } finally {
      udp.close()
    }
  }

  override def messageChannel[MessageType](
      implicit codec: Codec[MessageType]
  ): Observable[(InetSocketAddress, MessageType)] = {
    val pduCodec = derivePduCodec[MessageType]
    val messageChannel = new MessageChannel[InetSocketAddress, PDU[MessageType]](this)(pduCodec)
    decoderTable.put(pduCodec.typeCode.id, messageChannel.handleMessage)
    messageChannel.inboundMessages.map {
      case (_, pdu) =>
        (pdu.replyTo, pdu.sdu)
    }
  }

  private def derivePduCodec[MessageType](implicit codec: Codec[MessageType]): Codec[PDU[MessageType]] = {
    implicit val pduTc: TypeCode[PDU[MessageType]] = TypeCode.genTypeCode[PDU, MessageType]
    implicit val mpc: PartialCodec[MessageType] = codec.partialCodec
    Codec[PDU[MessageType]]
  }
  override val processAddress: InetSocketAddress = config.processAddress

  override def initialize(): Task[Unit] = Task.unit
}

object UDPPeerGroup {

  case class Config(bindAddress: InetSocketAddress, processAddress: InetSocketAddress)

  object Config {
    def apply(bindAddress: InetSocketAddress): Config = Config(bindAddress, bindAddress)
  }
  case class PDU[T](replyTo: InetSocketAddress, sdu: T)

  def create(
      config: Config
  )(implicit scheduler: Scheduler): Either[InitializationError, UDPPeerGroup] =
    PeerGroup.create(new UDPPeerGroup(config), config)

  def createOrThrow(config: Config)(implicit scheduler: Scheduler): UDPPeerGroup =
    PeerGroup.createOrThrow(new UDPPeerGroup(config), config)

}