package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.secs1.Secs1Message;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpCommunicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicator;
import com.shimizukenta.secs.secs1ontcpip.ext.multiclient.Secs1OnTcpIpMultiClientCommunicator;
import com.shimizukenta.secs.secs2.Secs2;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 表示单个客户端连接
 */
public class ClientConnection implements Closeable {

    private static final Logger logger = Logger.getLogger(ClientConnection.class.getName());

    private final AsynchronousSocketChannel channel;
    private final SocketAddress remoteAddress;
    private final Secs1OnTcpIpMultiClientCommunicator communicator;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final long creationTime;
    private long lastActivityTime;

    /**
     * 构造函数
     *
     * @param channel 客户端通道
     * @param remoteAddress 客户端地址
     * @param config 通信器配置
     * @throws IOException 如果创建通信器失败
     */
    public ClientConnection(AsynchronousSocketChannel channel, SocketAddress remoteAddress,
                           Secs1OnTcpIpReceiverCommunicatorConfig config) throws IOException {
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.communicator = Secs1OnTcpIpMultiClientCommunicator.newInstance(config, channel);
        this.creationTime = System.currentTimeMillis();
        this.lastActivityTime = this.creationTime;

        logger.info("创建客户端连接: " + remoteAddress);
    }

    /**
     * 获取客户端地址
     *
     * @return 客户端地址
     */
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * 获取客户端通道
     *
     * @return 客户端通道
     */
    public AsynchronousSocketChannel getChannel() {
        return channel;
    }

    /**
     * 获取通信器
     *
     * @return 通信器
     */
    public Secs1OnTcpIpMultiClientCommunicator getCommunicator() {
        return communicator;
    }

    /**
     * 更新最后活动时间
     */
    public void updateLastActivityTime() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * 获取最后活动时间
     *
     * @return 最后活动时间
     */
    public long getLastActivityTime() {
        return lastActivityTime;
    }

    /**
     * 获取创建时间
     *
     * @return 创建时间
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * 发送消息
     *
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param secs2 消息数据
     * @return 回复消息，如果没有回复则返回空
     * @throws SecsSendMessageException 如果发送消息失败
     * @throws InterruptedException 如果线程被中断
     */
    public Optional<SecsMessage> send(int stream, int function, boolean wbit, Secs2 secs2)
            throws SecsSendMessageException, InterruptedException {
        try {
            updateLastActivityTime();
            logger.info(String.format("向客户端 %s 发送消息: S%dF%d%s, 数据: %s",
                    remoteAddress, stream, function, (wbit ? " W" : ""), secs2));

            // 检查通道是否打开
            if (channel == null || !channel.isOpen()) {
                logger.warning("客户端通道已关闭: " + remoteAddress);
                throw new SecsSendMessageException("Client channel is closed: " + remoteAddress);
            }

            // 检查通信器是否打开
            if (!communicator.isOpen()) {
                logger.warning("通信器未打开: " + remoteAddress);
                throw new SecsSendMessageException("Communicator is not open: " + remoteAddress);
            }

            // 尝试发送消息
            logger.fine("开始发送消息...");
            Optional<SecsMessage> reply = communicator.send(stream, function, wbit, secs2);
            logger.fine("消息发送完成");

            if (reply.isPresent()) {
                logger.info(String.format("收到客户端 %s 的回复: S%dF%d%s, 数据: %s",
                        remoteAddress, reply.get().getStream(), reply.get().getFunction(),
                        (reply.get().wbit() ? " W" : ""), reply.get().secs2()));
            } else {
                logger.fine("未收到客户端回复");
            }

            return reply;
        } catch (SecsException e) {
            logger.log(Level.WARNING, "发送消息失败: " + e.getMessage(), e);

            // 检查是否是连接问题
            if (e.getMessage().contains("Not connect") ||
                e.getMessage().contains("Connection") ||
                e.getMessage().contains("Socket")) {
                logger.warning("检测到连接问题，标记连接为关闭");
                try {
                    close();
                } catch (IOException ex) {
                    logger.log(Level.WARNING, "关闭连接失败: " + ex.getMessage(), ex);
                }
            }

            throw new SecsSendMessageException(e);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "发送消息时发生未知异常: " + e.getMessage(), e);
            throw new SecsSendMessageException(e);
        }
    }

    /**
     * 打开连接
     *
     * @throws IOException 如果打开连接失败
     */
    public void open() throws IOException {
        if (!closed.get()) {
            try {
                // 检查通信器是否已经打开
                if (!communicator.isOpen()) {
                    logger.info("打开客户端通信器: " + remoteAddress);
                    communicator.open();
                    logger.info("客户端通信器已打开: " + remoteAddress);
                } else {
                    logger.info("客户端通信器已经打开: " + remoteAddress);
                }

                // 检查通道是否打开
                if (channel == null || !channel.isOpen()) {
                    logger.warning("客户端通道已关闭或为空: " + remoteAddress);
                    throw new IOException("Client channel is closed or null: " + remoteAddress);
                }

                logger.info("打开客户端连接成功: " + remoteAddress);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "打开客户端连接失败: " + e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "打开客户端连接时发生未知异常: " + e.getMessage(), e);
                throw new IOException("Failed to open client connection: " + e.getMessage(), e);
            }
        } else {
            logger.warning("尝试打开已关闭的连接: " + remoteAddress);
            throw new IOException("Connection is already closed: " + remoteAddress);
        }
    }

    /**
     * 关闭连接
     *
     * @throws IOException 如果关闭连接失败
     */
    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                communicator.close();
                channel.close();
                logger.info("关闭客户端连接: " + remoteAddress);
            } catch (IOException e) {
                logger.log(Level.WARNING, "关闭客户端连接失败: " + e.getMessage(), e);
                throw e;
            }
        }
    }

    /**
     * 检查连接是否已关闭
     *
     * @return 如果连接已关闭则返回true
     */
    public boolean isClosed() {
        return closed.get() || !channel.isOpen();
    }

    /**
     * 发送心跳消息
     *
     * @return 如果心跳成功则返回true
     */
    public boolean sendHeartbeat() {
        try {
            logger.fine("向客户端 " + remoteAddress + " 发送心跳消息 (S5F5 LinkTest.Request)");
            Optional<SecsMessage> reply = send(5, 5, true, Secs2.empty());

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                if (msg.getStream() == 5 && msg.getFunction() == 6) {
                    logger.fine("收到客户端 " + remoteAddress + " 的心跳回复: S5F6 LinkTest.Acknowledge");
                    return true;
                } else {
                    logger.warning(String.format("收到客户端 %s 的非标准心跳回复: S%dF%d",
                            remoteAddress, msg.getStream(), msg.getFunction()));
                }
            } else {
                logger.warning("未收到客户端 " + remoteAddress + " 的心跳回复");
            }

            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送心跳消息失败: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String toString() {
        return "ClientConnection{" +
                "remoteAddress=" + remoteAddress +
                ", closed=" + closed +
                '}';
    }
}
