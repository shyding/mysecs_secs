package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端连接管理器
 */
public class ClientConnectionManager implements Closeable {

    private static final Logger logger = Logger.getLogger(ClientConnectionManager.class.getName());

    private final Map<SocketAddress, ClientConnection> connections = new ConcurrentHashMap<>();
    private final MessageSourceTracker messageSourceTracker = new MessageSourceTracker();
    private final Secs1OnTcpIpReceiverCommunicatorConfig config;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final long heartbeatInterval;
    private final long connectionTimeout;

    /**
     * 构造函数
     *
     * @param config 通信器配置
     * @param heartbeatInterval 心跳间隔（毫秒）
     * @param connectionTimeout 连接超时（毫秒）
     */
    public ClientConnectionManager(Secs1OnTcpIpReceiverCommunicatorConfig config,
                                  long heartbeatInterval, long connectionTimeout) {
        this.config = config;
        this.heartbeatInterval = heartbeatInterval;
        this.connectionTimeout = connectionTimeout;

        // 启动心跳和超时检查
        scheduler.scheduleAtFixedRate(this::checkConnections,
                heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * 添加客户端连接
     *
     * @param channel 客户端通道
     * @param remoteAddress 客户端地址
     * @throws IOException 如果创建连接失败
     */
    public void addConnection(AsynchronousSocketChannel channel, SocketAddress remoteAddress) throws IOException {
        try {
            if (connections.containsKey(remoteAddress)) {
                logger.warning("客户端 " + remoteAddress + " 已存在，关闭旧连接");
                removeConnection(remoteAddress);
            }

            // 创建新的客户端连接
            logger.info("创建新的客户端连接: " + remoteAddress);
            ClientConnection connection = new ClientConnection(channel, remoteAddress, config);

            // 打开连接
            logger.info("打开客户端连接: " + remoteAddress);
            connection.open();

            // 添加到连接映射
            connections.put(remoteAddress, connection);

            // 记录接收时间
            trackReceiveTime(System.currentTimeMillis(), remoteAddress);

            logger.info("添加客户端连接成功: " + remoteAddress);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "添加客户端连接失败: " + e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "添加客户端连接时发生未知异常: " + e.getMessage(), e);
            throw new IOException("Failed to add client connection: " + e.getMessage(), e);
        }
    }

    /**
     * 移除客户端连接
     *
     * @param remoteAddress 客户端地址
     */
    public void removeConnection(SocketAddress remoteAddress) {
        ClientConnection connection = connections.remove(remoteAddress);
        if (connection != null) {
            try {
                connection.close();
                messageSourceTracker.clearClientMessages(remoteAddress);
                logger.info("移除客户端连接: " + remoteAddress);
            } catch (IOException e) {
                logger.log(Level.WARNING, "关闭客户端连接失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 获取客户端连接
     *
     * @param remoteAddress 客户端地址
     * @return 客户端连接，如果不存在则返回null
     */
    public ClientConnection getConnection(SocketAddress remoteAddress) {
        return connections.get(remoteAddress);
    }

    /**
     * 获取所有客户端连接
     *
     * @return 所有客户端连接
     */
    public Collection<ClientConnection> getAllConnections() {
        return connections.values();
    }

    /**
     * 获取客户端连接数
     *
     * @return 客户端连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }

    /**
     * 检查连接状态
     */
    private void checkConnections() {
        try {
            logger.fine("检查连接状态，当前连接数: " + connections.size());

            long currentTime = System.currentTimeMillis();
            List<SocketAddress> toRemove = new ArrayList<>();

            for (Map.Entry<SocketAddress, ClientConnection> entry : connections.entrySet()) {
                SocketAddress addr = entry.getKey();
                ClientConnection conn = entry.getValue();

                // 检查连接是否已关闭
                if (conn.isClosed()) {
                    logger.info("客户端连接已关闭: " + addr);
                    toRemove.add(addr);
                    continue;
                }

                // 检查连接是否超时
                if (currentTime - conn.getLastActivityTime() > connectionTimeout) {
                    // 发送心跳
                    boolean heartbeatSuccess = conn.sendHeartbeat();

                    if (!heartbeatSuccess) {
                        logger.warning("客户端连接超时: " + addr);
                        toRemove.add(addr);
                    }
                }
            }

            // 移除关闭和超时的连接
            for (SocketAddress addr : toRemove) {
                removeConnection(addr);
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "检查连接状态时发生异常: " + e.getMessage(), e);
        }
    }

    /**
     * 跟踪消息来源
     *
     * @param message 消息
     * @param sourceAddress 来源地址
     */
    public void trackMessageSource(SecsMessage message, SocketAddress sourceAddress) {
        messageSourceTracker.trackMessageSource(message, sourceAddress);
    }

    /**
     * 跟踪接收时间
     *
     * @param time 接收时间
     * @param sourceAddress 来源地址
     */
    public void trackReceiveTime(long time, SocketAddress sourceAddress) {
        messageSourceTracker.trackReceiveTime(time, sourceAddress);
    }

    /**
     * 获取消息来源
     *
     * @param message 消息
     * @return 来源地址，如果未知则返回null
     */
    public SocketAddress getMessageSource(SecsMessage message) {
        return messageSourceTracker.getMessageSource(message);
    }

    /**
     * 关闭所有连接
     *
     * @throws IOException 如果关闭连接失败
     */
    @Override
    public void close() throws IOException {
        scheduler.shutdown();

        for (ClientConnection connection : connections.values()) {
            try {
                connection.close();
            } catch (IOException e) {
                logger.log(Level.WARNING, "关闭客户端连接失败: " + e.getMessage(), e);
            }
        }

        connections.clear();
        logger.info("关闭所有客户端连接");
    }
}
