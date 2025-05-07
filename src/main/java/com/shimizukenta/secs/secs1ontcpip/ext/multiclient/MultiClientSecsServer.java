package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.*;
import com.shimizukenta.secs.gem.Gem;
import com.shimizukenta.secs.impl.AbstractSecsCommunicator;
import com.shimizukenta.secs.secs1.Secs1MessageReceiveBiListener;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpLogObservable;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 多客户端SECS服务器
 */
public class MultiClientSecsServer  implements Closeable {

    private static final Logger logger = Logger.getLogger(MultiClientSecsServer.class.getName());

    private final Secs1OnTcpIpReceiverCommunicatorConfig config;
    private final String host;
    private final int port;
    private final long heartbeatInterval;
    private final long connectionTimeout;

    private final ClientConnectionManager connectionManager;
    private final ExecutorService executor;
    private final AsynchronousChannelGroup channelGroup;
    private AsynchronousServerSocketChannel serverChannel;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);



    private Consumer<ClientConnection> clientConnectedHandler;
    private Consumer<ClientConnection> clientDisconnectedHandler;
    private BiConsumer<SecsMessage, SocketAddress> messageReceivedHandler;
    SecsMessageReceiveListener listener;
    private SecsLogListener secsLogListener;
    private SecsMessagePassThroughListener  secsMessagePassThroughListener;
    private SecsCommunicatableStateChangeListener secsCommunicatableStateChangeListener;

    /**
     * 构造函数
     *
     * @param config 通信器配置
     * @param host 主机地址
     * @param port 端口
     * @param heartbeatInterval 心跳间隔（毫秒）
     * @param connectionTimeout 连接超时（毫秒）
     * @throws IOException 如果创建服务器失败
     */
    public MultiClientSecsServer(Secs1OnTcpIpReceiverCommunicatorConfig config,
                                String host, int port,
                                long heartbeatInterval, long connectionTimeout) throws IOException {
        this.config = config;
        this.host = host;
        this.port = port;
        this.heartbeatInterval = heartbeatInterval;
        this.connectionTimeout = connectionTimeout;

        this.connectionManager = new ClientConnectionManager(config, heartbeatInterval, connectionTimeout);
        this.executor = Executors.newCachedThreadPool();
        this.channelGroup = AsynchronousChannelGroup.withThreadPool(executor);
    }

    /**
     * 构造函数 - 使用配置中的地址和默认的心跳间隔和连接超时
     *
     * @param config 通信器配置
     * @throws IOException 如果创建服务器失败
     */
    public MultiClientSecsServer(Secs1OnTcpIpReceiverCommunicatorConfig config) throws IOException {
        this(config,
             config.socketAddress() instanceof InetSocketAddress ?
                 ((InetSocketAddress)config.socketAddress()).getHostString() : "0.0.0.0",
             config.socketAddress() instanceof InetSocketAddress ?
                 ((InetSocketAddress)config.socketAddress()).getPort() : 5000,
             30000, // 默认心跳间隔30秒
             60000  // 默认连接超时60秒
        );
    }

    /**
     * 设置客户端连接处理器
     *
     * @param handler 处理器
     */
    public void setClientConnectedHandler(Consumer<ClientConnection> handler) {
        this.clientConnectedHandler = handler;
    }

    /**
     * 设置客户端断开连接处理器
     *
     * @param handler 处理器
     */
    public void setClientDisconnectedHandler(Consumer<ClientConnection> handler) {
        this.clientDisconnectedHandler = handler;
    }

    /**
     * 设置消息接收处理器
     *
     * @param handler 处理器
     */
    public void setMessageReceivedHandler(BiConsumer<SecsMessage, SocketAddress> handler) {
        this.messageReceivedHandler = handler;
    }

    /**
     * 添加SECS消息接收监听器
     *
     * @param listener 监听器
     */
    public void addSecsMessageReceiveListener(SecsMessageReceiveListener listener) {
        this.listener = listener;
        // 对所有客户端连接添加消息监听器
        for (ClientConnection connection : connectionManager.getAllConnections()) {
            connection.getCommunicator().addSecsMessageReceiveListener(listener);
        }

        // 对新客户端连接添加消息监听器
        setClientConnectedHandler(conn -> {
            conn.getCommunicator().addSecsMessageReceiveListener(listener);
        });
    }

    /**
     * 获取消息来源
     *
     * @param message 消息
     * @return 消息来源地址，如果不存在则返回空
     */
    public Optional<SocketAddress> getMessageSource(SecsMessage message) {
        return connectionManager.getMessageSourceTracker().getMessageSource(message);
    }

    /**
     * 获取所有客户端连接
     *
     * @return 客户端连接集合
     */
    public Collection<ClientConnection> getConnections() {
        return connectionManager.getAllConnections();
    }

    /**
     * 启动服务器
     *
     * @throws IOException 如果启动服务器失败
     */
    public void start() throws IOException {
        if (closed.get()) {
            throw new IOException("Server is closed");
        }

        if (running.compareAndSet(false, true)) {
            try {
                // 创建服务器通道
                serverChannel = AsynchronousServerSocketChannel.open(channelGroup);
                serverChannel.bind(new InetSocketAddress(host, port));

                // 开始接受连接
                acceptConnections();

                logger.info("服务器已启动，监听地址: " + host + ":" + port);
            } catch (IOException e) {
                running.set(false);
                logger.log(Level.SEVERE, "启动服务器失败: " + e.getMessage(), e);
                throw e;
            }
        }
    }

    /**
     * 打开服务器（与start方法相同）
     *
     * @throws IOException 如果打开服务器失败
     */
    public void open() throws IOException {
        start();
    }

    /**
     * 接受连接
     */
    private void acceptConnections() {
        if (!running.get() || closed.get()) {
            return;
        }

        serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel channel, Void attachment) {
                // 继续接受下一个连接
                acceptConnections();

                // 处理当前连接
                handleNewConnection(channel);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                if (running.get() && !closed.get()) {
                    logger.log(Level.WARNING, "接受连接失败: " + exc.getMessage(), exc);

                    // 继续接受下一个连接
                    acceptConnections();
                }
            }
        });
    }

    /**
     * 处理新连接
     *
     * @param channel 客户端通道
     */
    private void handleNewConnection(AsynchronousSocketChannel channel) {
        try {
            SocketAddress remoteAddress = channel.getRemoteAddress();
            logger.info("接受新连接: " + remoteAddress);

            // 添加连接
            connectionManager.addConnection(channel, remoteAddress);

            // 获取连接对象
            ClientConnection connection = connectionManager.getConnection(remoteAddress);

            if (connection != null) {
                try {
                    // 注册消息监听器
                    if (hostReceiveListener != null) {
                        connection.getCommunicator().addSecs1MessageReceiveBiListener(hostReceiveListener);
                    } else {
                    }

                    if (this.listener != null) {
                        connection.getCommunicator().addSecsMessageReceiveListener(this.listener);
                    }
                    if(Objects.nonNull(secsLogListener)){
                        connection.getCommunicator().addSecsLogListener(secsLogListener);
                    }
                    if(Objects.nonNull(secsMessagePassThroughListener)){
                        connection.getCommunicator().addSendedSecs1MessagePassThroughListener(secsMessagePassThroughListener);

                    }
                    if(Objects.nonNull(secsCommunicatableStateChangeListener)){
                        connection.getCommunicator().addSecsCommunicatableStateChangeListener(secsCommunicatableStateChangeListener);
                    }

                    // 打开连接
                    if (!connection.getCommunicator().isOpen()) {
                        logger.info("打开客户端通信器: " + remoteAddress);
                        connection.getCommunicator().open();
                    }



                    // 设置消息接收监听器
                    connection.getCommunicator().addSecsMessageReceiveListener(message -> {
                        try {
                            // 更新最后活动时间
                            connection.updateLastActivityTime();

                            // 记录消息来源
                            connectionManager.trackMessageSource(message, remoteAddress);

                            // 记录接收时间
                            connectionManager.trackReceiveTime(System.currentTimeMillis(), remoteAddress);

                            logger.info(String.format("收到来自 %s 的消息: S%dF%d%s, 数据: %s",
                                    remoteAddress, message.getStream(), message.getFunction(),
                                    (message.wbit() ? " W" : ""), message.secs2()));


                            // 调用消息处理器
                            if (messageReceivedHandler != null) {
                                messageReceivedHandler.accept(message, remoteAddress);
                            }


                        } catch (Exception e) {
                            logger.log(Level.WARNING, "处理消息时发生异常: " + e.getMessage(), e);
                            System.out.println("处理消息时发生异常: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });

                    // 添加所有日志监听器，记录通信活动
                    connection.getCommunicator().addSecsLogListener(log -> {
                        try {
                            // 更新最后活动时间
                            connection.updateLastActivityTime();

                            // 记录所有通信日志
                            String logStr = log.toString();
                            if (logStr.contains("ENQ") || logStr.contains("EOT") ||
                                logStr.contains("ACK") || logStr.contains("NAK")) {
                                logger.info("SECS-I协议消息: " + logStr + " - " + remoteAddress);
                                System.out.println("SECS-I协议消息: " + logStr + " - " + remoteAddress);
                            }
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "处理日志消息失败: " + e.getMessage(), e);
                            System.out.println("处理日志消息失败: " + e.getMessage());
                        }
                    });


                    // 调用客户端连接处理器
                    if (clientConnectedHandler != null) {
                        logger.info("调用客户端连接处理器: " + remoteAddress);
                        System.out.println("调用客户端连接处理器: " + remoteAddress);
                        clientConnectedHandler.accept(connection);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "设置客户端连接失败: " + e.getMessage(), e);
                    System.out.println("设置客户端连接失败: " + e.getMessage());
                    connectionManager.removeConnection(remoteAddress);
                }
            } else {
                logger.warning("无法获取客户端连接对象: " + remoteAddress);
                System.out.println("无法获取客户端连接对象: " + remoteAddress);
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "处理新连接失败: " + e.getMessage(), e);

        }
    }







    /**
     * 向客户端发送消息
     *
     * @param clientAddress 客户端地址
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param secs2 消息数据
     * @return 回复消息，如果没有回复则返回空
     * @throws SecsSendMessageException 如果发送消息失败
     * @throws InterruptedException 如果线程被中断
     */
    public Optional<SecsMessage> sendToClient(SocketAddress clientAddress, int stream, int function,
                                             boolean wbit, Secs2 secs2)
            throws SecsSendMessageException, InterruptedException {


        // 获取客户端连接
        ClientConnection connection = connectionManager.getConnection(clientAddress);

        if (connection == null) {
            logger.warning("找不到客户端连接: " + clientAddress);
            throw new SecsSendMessageException("Client connection not found: " + clientAddress);
        }

        if (connection.isClosed()) {
            logger.warning("客户端连接已关闭: " + clientAddress);
            throw new SecsSendMessageException("Client connection is closed: " + clientAddress);
        }



        // 发送消息
        try {


            Optional<SecsMessage> result = connection.send(stream, function, wbit, secs2);


            return result;
        } catch (Exception e) {

            e.printStackTrace();
            throw e;
        }
    }

    public void send(SocketAddress clientAddress,SecsMessage message, int stream, int function, boolean wbit) throws InterruptedException, SecsException {
         send(clientAddress, message,stream,function,wbit,Secs2.empty());
    }
    /**
     * 向客户端发送消息（简化版）
     *
     * @param clientAddress 客户端地址
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param secs2 消息数据
     * @throws SecsSendMessageException 如果发送消息失败
     * @throws SecsWaitReplyMessageException 如果等待回复超时
     * @throws SecsException 如果发生其他SECS异常
     * @throws InterruptedException 如果线程被中断
     */
    public void send(SocketAddress clientAddress,SecsMessage message, int stream, int function, boolean wbit, Secs2 secs2)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {
        ClientConnection connection = connectionManager.getConnection(clientAddress);
        if (connection != null && !connection.isClosed()) {
            connection.getCommunicator().send(message,stream, function, wbit, secs2);
        } else {
            logger.warning("找不到客户端连接或连接已关闭: " + clientAddress);
            throw new SecsSendMessageException("Client connection not found or closed: " + clientAddress);
        }
    }

    public void send(SocketAddress clientAddress, int stream, int function, boolean wbit, Secs2 secs2)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {
        ClientConnection connection = connectionManager.getConnection(clientAddress);
        if (connection != null && !connection.isClosed()) {
            connection.getCommunicator().send(stream, function, wbit, secs2);

        } else {
            logger.warning("找不到客户端连接或连接已关闭: " + clientAddress);
            throw new SecsSendMessageException("Client connection not found or closed: " + clientAddress);
        }
    }

    /**
     * 向客户端发送消息并等待回复
     * @param clientAddress
     * @return
     */
    public Gem gem(SocketAddress clientAddress) {
        ClientConnection connection = connectionManager.getConnection(clientAddress);
        if (connection != null && !connection.isClosed()) {
            Gem gem = connection.getCommunicator().gem();
        }
        return null;
    }

    /**
     * 向客户端发送消息并等待回复
     * @param message
     * @return
     */
    public Gem gem(SecsMessage message ){

        return gem(message.getSourceAddress());
    }

    public void send(SecsMessage message, int stream, int function, boolean wbit, Secs2 secs2) throws InterruptedException, SecsException {
        send(message.getSourceAddress(), message,stream,function,wbit,secs2);
    }

    public void send(SecsMessage message, int stream, int function, boolean wbit) throws InterruptedException, SecsException {
        send(message.getSourceAddress(), message,stream,function,wbit,Secs2.empty());
    }

    /**
     * 向客户端发送消息并等待回复
     *
     * @param clientAddress 客户端地址
     * @param stream 流ID
     * @param function 功能ID
     * @param secs2 消息数据
     * @return 回复消息
     * @throws SecsSendMessageException 如果发送消息失败
     * @throws SecsWaitReplyMessageException 如果等待回复超时
     * @throws SecsException 如果发生其他SECS异常
     * @throws InterruptedException 如果线程被中断
     */
    public SecsMessage sendAndWaitReply(SocketAddress clientAddress, int stream, int function, Secs2 secs2)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {
        ClientConnection connection = connectionManager.getConnection(clientAddress);
        if (connection != null && !connection.isClosed()) {
            return connection.getCommunicator().sendAndWaitReply(stream, function, secs2);
        } else {
            logger.warning("找不到客户端连接或连接已关闭: " + clientAddress);
            throw new SecsSendMessageException("Client connection not found or closed: " + clientAddress);
        }
    }

    /**
     * 向客户端发送消息并等待回复，带超时
     *
     * @param clientAddress 客户端地址
     * @param stream 流ID
     * @param function 功能ID
     * @param secs2 消息数据
     * @param timeout 超时时间（毫秒）
     * @return 回复消息
     * @throws SecsSendMessageException 如果发送消息失败
     * @throws SecsWaitReplyMessageException 如果等待回复超时
     * @throws SecsException 如果发生其他SECS异常
     * @throws InterruptedException 如果线程被中断
     */
    public SecsMessage sendAndWaitReply(SocketAddress clientAddress, int stream, int function, Secs2 secs2, long timeout)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {
        ClientConnection connection = connectionManager.getConnection(clientAddress);
        if (connection != null && !connection.isClosed()) {
            return connection.getCommunicator().sendAndWaitReply(stream, function, secs2, timeout);
        } else {
            logger.warning("找不到客户端连接或连接已关闭: " + clientAddress);
            throw new SecsSendMessageException("Client connection not found or closed: " + clientAddress);
        }
    }

    /**
     * 向所有客户端广播消息
     *
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param secs2 消息数据
     * @throws InterruptedException 如果线程被中断
     */
    public void broadcastToAllClients(int stream, int function, boolean wbit, Secs2 secs2)
            throws InterruptedException {
        Collection<ClientConnection> connections = connectionManager.getAllConnections();
        logger.info(String.format("向所有客户端广播消息: S%dF%d%s, 客户端数: %d",
                stream, function, (wbit ? " W" : ""), connections.size()));

        for (ClientConnection connection : connections) {
            try {
                if (!connection.isClosed()) {
                    connection.send(stream, function, wbit, secs2);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "向客户端 " + connection.getRemoteAddress() +
                        " 广播消息失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 获取客户端连接
     *
     * @param clientAddress 客户端地址
     * @return 客户端连接，如果不存在则返回null
     */
    public ClientConnection getClientConnection(SocketAddress clientAddress) {
        return connectionManager.getConnection(clientAddress);
    }

    /**
     * 获取所有客户端连接
     *
     * @return 所有客户端连接
     */
    public Collection<ClientConnection> getAllClientConnections() {
        return connectionManager.getAllConnections();
    }

    /**
     * 获取客户端连接数
     *
     * @return 客户端连接数
     */
    public int getClientCount() {
        return connectionManager.getConnectionCount();
    }

    /**
     * 停止服务器
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("停止服务器");
        }
    }

    /**
     * 关闭服务器
     *
     * @throws IOException 如果关闭服务器失败
     */
    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                stop();

                // 关闭服务器通道
                if (serverChannel != null) {
                    serverChannel.close();
                }

                // 关闭连接管理器
                connectionManager.close();

                // 关闭通道组和执行器
                channelGroup.shutdownNow();
                executor.shutdown();

                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                logger.info("服务器已关闭");
            } catch (IOException e) {
                logger.log(Level.SEVERE, "关闭服务器失败: " + e.getMessage(), e);
                throw e;
            }
        }
    }

    /**
     * 检查服务器是否正在运行
     *
     * @return 如果服务器正在运行则返回true
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 检查服务器是否已关闭
     *
     * @return 如果服务器已关闭则返回true
     */
    public boolean isClosed() {
        return closed.get();
    }

    Secs1MessageReceiveBiListener hostReceiveListener;

    public void addSecs1MessageReceiveBiListener(Secs1MessageReceiveBiListener hostReceiveListener) {
       this.hostReceiveListener= hostReceiveListener;
    }

    /**
     * 获取连接管理器
     *
     * @return 连接管理器
     */
    public ClientConnectionManager getConnectionManager() {
        return this.connectionManager;
    }

    protected void addSecsLogListener(SecsLogListener secsLogListener) {
        
        this.secsLogListener = secsLogListener;
    }

    protected void addReceiveMessagePassThroughListener(SecsMessagePassThroughListener secsMessagePassThroughListener) {
           this.secsMessagePassThroughListener = secsMessagePassThroughListener;
        }

    protected void addSecsCommunicatableStateChangeListener(SecsCommunicatableStateChangeListener secsCommunicatableStateChangeListener) {
             this.secsCommunicatableStateChangeListener = secsCommunicatableStateChangeListener;
    }
}
