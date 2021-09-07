package example.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.rsocket.metadata.TaggingMetadataCodec;
import io.rsocket.metadata.WellKnownMimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        InetSocketAddress address = new InetSocketAddress("localhost", 7000);
        Bootstrap bootstrap = new Bootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(
                        new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new RSocketChannelOverflowHandler());
                            }
                        });

        ChannelFuture connect = bootstrap.connect(address);
        connect.awaitUninterruptibly();
        logger.info("connected to {}", address);
        connect.channel().closeFuture().awaitUninterruptibly();
    }

    private static final class RSocketChannelOverflowHandler extends ChannelDuplexHandler {
        private static final byte[] METADATA_TYPE = "message/x.rsocket.composite-metadata.v0".getBytes(StandardCharsets.UTF_8);
        private static final byte[] DATA_TYPE = "application/cbor".getBytes(StandardCharsets.UTF_8);

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);

            ByteBufAllocator allocator = ctx.alloc();

            /*setup frame*/

            ByteBuf setupFrame = allocator.buffer();
            setupFrame/*streamId*/
                    .writeInt(0)
                    /*flags*/
                    .writeShort(/*FrameType.SETUP*/0x01 << 10)
                    /*version*/
                    .writeInt(1 << 16)
                    /*keep-alive interval*/
                    .writeInt(100_000)
                    /*keep-alive timeout*/
                    .writeInt(1_000_000)
                    /*metadata type*/
                    .writeByte(METADATA_TYPE.length).writeBytes(METADATA_TYPE)
                    /*data type*/
                    .writeByte(DATA_TYPE.length).writeBytes(DATA_TYPE);

            ByteBuf setupLengthPrefix = encodeLength(allocator, setupFrame.readableBytes());

            ctx.write(setupLengthPrefix);
            ctx.writeAndFlush(setupFrame);

            /*request-channel frame*/

            /*spring's request metadata dance*/
            CompositeByteBuf metadata = allocator.compositeBuffer();
            ByteBuf routeMetadata = TaggingMetadataCodec.createRoutingMetadata(
                    allocator, Collections.singletonList("slow-channel")).getContent();

            io.rsocket.metadata.CompositeMetadataCodec.encodeAndAddMetadata(metadata, allocator,
                    WellKnownMimeType.MESSAGE_RSOCKET_ROUTING, routeMetadata);

            ByteBuf metadataLengthPrefix = encodeLength(allocator, metadata.readableBytes());

            /*request data*/
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                content.append("channel-overflow");
            }
            Request request = new Request();
            request.setMessage(content.toString());
            ObjectMapper mapper = new CBORMapper();

            ByteBuf requestFrame = allocator.buffer();
            requestFrame/*streamId*/
                    .writeInt(1)
                    /*flags*/
                    .writeShort(/*FrameType.REQUEST_CHANNEL*/0x07 << 10 | /*metadata*/ 1 << 8)
                    /*requestN*/
                    .writeInt(42);
            ByteBuf data = Unpooled.wrappedBuffer(mapper.writeValueAsBytes(request));

            ByteBuf requestLengthPrefix = encodeLength(allocator,
                    requestFrame.readableBytes() + metadataLengthPrefix.readableBytes() +
                            metadata.readableBytes() + data.readableBytes());

            ctx.write(requestLengthPrefix);
            ctx.write(requestFrame);
            ctx.write(metadataLengthPrefix);
            ctx.write(metadata);
            ctx.writeAndFlush(data);

            /*send payloads periodically, without accounting received requestN*/
            ctx.channel().eventLoop().scheduleAtFixedRate(() -> {
                for (int i = 0; i < 5000; i++) {
                    /*payload frame*/
                    ByteBuf payloadFrame = allocator.buffer();
                    payloadFrame/*streamId*/
                            .writeInt(1)
                            /*flags*/
                            .writeShort(/*FrameType.PAYLOAD*/0x0A << 10 | /*next flag*/ 1 << 5);

                    ByteBuf payloadData;
                    try {
                        payloadData = Unpooled.wrappedBuffer(mapper.writeValueAsBytes(request));
                    } catch (JsonProcessingException e) {
                        logger.error("cbor mapper error", e);
                        ctx.close();
                        payloadFrame.release();
                        return;
                    }
                    ByteBuf payloadLengthPrefix = encodeLength(allocator, payloadFrame.readableBytes()
                            + payloadData.readableBytes());
                    ctx.write(payloadLengthPrefix);
                    ctx.write(payloadFrame);
                    ctx.writeAndFlush(payloadData);
                    if (!ctx.channel().isWritable()) {
                        ctx.flush();
                    }
                }

            }, 0, 100, TimeUnit.MILLISECONDS);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            } else {
                super.channelRead(ctx, msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            logger.info("Connection closed");
            super.channelInactive(ctx);
        }

        private ByteBuf encodeLength(ByteBufAllocator allocator, int length) {
            ByteBuf lengthPrefix = allocator.buffer(3);
            lengthPrefix.writeByte(length >> 16);
            lengthPrefix.writeByte(length >> 8);
            lengthPrefix.writeByte(length);
            return lengthPrefix;
        }
    }

    static class Request {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
