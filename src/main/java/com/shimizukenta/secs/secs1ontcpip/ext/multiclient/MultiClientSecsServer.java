package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.*;
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
public class MultiClientSecsServer implements Closeable {

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
            System.out.println("接受新连接: " + remoteAddress);

            // 添加连接
            connectionManager.addConnection(channel, remoteAddress);

            // 获取连接对象
            ClientConnection connection = connectionManager.getConnection(remoteAddress);

            if (connection != null) {
                try {
                    // 注册消息监听器
                    if (hostReceiveListener != null) {
                        System.out.println("注册 hostReceiveListener 到客户端通信器: " + remoteAddress);
                        connection.getCommunicator().addSecs1MessageReceiveBiListener(hostReceiveListener);
                    } else {
                        System.out.println("hostReceiveListener 为空，不注册");
                    }

                    if (this.listener != null) {
                        System.out.println("注册 SecsMessageReceiveListener 到客户端通信器: " + remoteAddress);
                        connection.getCommunicator().addSecsMessageReceiveListener(this.listener);
                    } else {
                        System.out.println("SecsMessageReceiveListener 为空，不注册");
                    }

                    // 打开连接
                    if (!connection.getCommunicator().isOpen()) {
                        logger.info("打开客户端通信器: " + remoteAddress);
                        System.out.println("打开客户端通信器: " + remoteAddress);
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
                            System.out.println(String.format("收到来自 %s 的消息: S%dF%d%s",
                                    remoteAddress, message.getStream(), message.getFunction(),
                                    (message.wbit() ? " W" : "")));

                            // 调用消息处理器
                            if (messageReceivedHandler != null) {
                                messageReceivedHandler.accept(message, remoteAddress);
                            }

                            // 处理标准消息
                            handleStandardMessage(message, remoteAddress);

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
            System.out.println("处理新连接失败: " + e.getMessage());
        }
    }

    /**
     * 处理标准消息
     *
     * @param message 消息
     * @param sourceAddress 来源地址
     */
    private void handleStandardMessage(SecsMessage message, SocketAddress sourceAddress) {
        try {
            int stream = message.getStream();
            int function = message.getFunction();
            boolean wbit = message.wbit();

            logger.info(String.format("收到来自 %s 的消息: S%dF%d%s",
                    sourceAddress, stream, function, (wbit ? " W" : "")));

            // 处理标准消息
            switch (stream) {
                case 1:
                    handleStream1Message(message, sourceAddress);
                    break;
                case 2:
                    handleStream2Message(message, sourceAddress);
                    break;
                case 5:
                    handleStream5Message(message, sourceAddress);
                    break;
                case 6:
                    handleStream6Message(message, sourceAddress);
                    break;
                default:
                    // 其他流ID
                    if (wbit) {
                        // 如果需要回复，发送SxF0响应
                        sendSxF0Response(message, sourceAddress);
                    }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "处理标准消息时发生异常: " + e.getMessage(), e);
        }
    }

    /**
     * 处理Stream 1消息
     *
     * @param message 消息
     * @param sourceAddress 来源地址
     * @throws InterruptedException 如果线程被中断
     * @throws SecsException 如果发送消息失败
     */
    private void handleStream1Message(SecsMessage message, SocketAddress sourceAddress)
            throws InterruptedException, SecsException {
        int function = message.getFunction();
        boolean wbit = message.wbit();

        switch (function) {
            case 1: {
                // S1F1 W - Are You There
                if (wbit) {
                    // 回复S1F2 - On Line Data
                    Secs2 reply = Secs2.list(
                        Secs2.ascii("MULTI-CLIENT-SERVER"),  // 设备型号
                        Secs2.ascii("1.0.0")     // 软件版本
                    );

                    sendToClient(sourceAddress, 1, 2, false, reply);
                    logger.info("发送S1F2回复到客户端: " + sourceAddress);
                    System.out.println("发送S1F2回复到客户端: " + sourceAddress);

                    // 注意：根据SECS-I协议标准，服务器应该只发送标准的S1F2回复
                    // 不应该在S1F1/S1F2交换后发送额外的消息
                    // 如果需要发送欢迎消息，应该在应用层处理，而不是在协议层
                }
                break;
            }
            case 13: {
                // S1F13 W - Establish Communications Request
                if (wbit) {
                    // 回复S1F14 - Establish Communications Request Acknowledge
                    // 0x00 = 通信已建立
                    sendToClient(sourceAddress, 1, 14, false, Secs2.binary((byte)0x00));
                    logger.info("发送S1F14回复到客户端: " + sourceAddress + ", 通信已建立");
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S1F0响应
                    sendSxF0Response(message, sourceAddress);
                }
            }
        }
    }

    /**
     * 处理Stream 2消息
     *
     * @param message 消息
     * @param sourceAddress 来源地址
     * @throws InterruptedException 如果线程被中断
     * @throws SecsException 如果发送消息失败
     */
    private void handleStream2Message(SecsMessage message, SocketAddress sourceAddress)
            throws InterruptedException, SecsException {
        int function = message.getFunction();
        boolean wbit = message.wbit();

        switch (function) {
            case 21: {
                // S2F21 W - Remote Command
                if (wbit) {
                    try {
                        // 解析命令
                        String command = message.secs2().getAscii();
                        logger.info("收到来自 " + sourceAddress + " 的远程命令: " + command);

                        // 回复S2F22 - Remote Command Acknowledge
                        // 0x00 = 接受命令
                        sendToClient(sourceAddress, 2, 22, false, Secs2.binary((byte)0x00));
                        logger.info("发送S2F22回复到客户端: " + sourceAddress + ", 接受远程命令");
                    } catch (Exception e) {
                        // 命令格式错误
                        // 0x01 = 拒绝命令
                        sendToClient(sourceAddress, 2, 22, false, Secs2.binary((byte)0x01));
                        logger.warning("发送S2F22回复到客户端: " + sourceAddress + ", 拒绝远程命令 - 格式错误");
                    }
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S2F0响应
                    sendSxF0Response(message, sourceAddress);
                }
            }
        }
    }

    /**
     * 处理Stream 5消息
     *
     * @param message 消息
     * @param sourceAddress 来源地址
     * @throws InterruptedException 如果线程被中断
     * @throws SecsException 如果发送消息失败
     */
    private void handleStream5Message(SecsMessage message, SocketAddress sourceAddress)
            throws InterruptedException, SecsException {
        int function = message.getFunction();
        boolean wbit = message.wbit();

        switch (function) {
            case 1: {
                // S5F1 W - Alarm Report Send
                if (wbit) {
                    try {
                        // 解析报警数据
                        Secs2 data = message.secs2();
                        int alarmId = data.get(0).getInt();
                        String alarmText = data.get(1).getAscii();

                        logger.info(String.format("收到来自 %s 的报警报告: ID=%d, Text=%s",
                                sourceAddress, alarmId, alarmText));

                        // 回复S5F2 - Alarm Report Acknowledge
                        // 0x00 = 接受报警
                        sendToClient(sourceAddress, 5, 2, false, Secs2.binary((byte)0x00));
                        logger.info("发送S5F2回复到客户端: " + sourceAddress + ", 接受报警报告");
                    } catch (Exception e) {
                        // 报警数据格式错误
                        // 0x01 = 拒绝报警
                        sendToClient(sourceAddress, 5, 2, false, Secs2.binary((byte)0x01));
                        logger.warning("发送S5F2回复到客户端: " + sourceAddress + ", 拒绝报警报告 - 格式错误");
                    }
                }
                break;
            }
            case 5: {
                // S5F5 W - Link Test Request
                if (wbit) {
                    // 回复S5F6 - Link Test Acknowledge
                    sendToClient(sourceAddress, 5, 6, false, Secs2.empty());
                    logger.info("发送S5F6回复到客户端: " + sourceAddress + ", 链路测试确认");
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S5F0响应
                    sendSxF0Response(message, sourceAddress);
                }
            }
        }
    }

    /**
     * 处理Stream 6消息
     *
     * @param message 消息
     * @param sourceAddress 来源地址
     * @throws InterruptedException 如果线程被中断
     * @throws SecsException 如果发送消息失败
     */
    private void handleStream6Message(SecsMessage message, SocketAddress sourceAddress)
            throws InterruptedException, SecsException {
        int function = message.getFunction();
        boolean wbit = message.wbit();

        switch (function) {
            case 11: {
                // S6F11 W - Event Report Send
                if (wbit) {
                    try {
                        // 解析事件报告数据
                        Secs2 data = message.secs2();
                        int eventId = data.get(0).getInt();
                        int reportId = data.get(1).getInt();
                        Secs2 reportData = data.get(2);

                        logger.info(String.format("收到来自 %s 的事件报告: EventID=%d, ReportID=%d, Data=%s",
                                sourceAddress, eventId, reportId, reportData));

                        // 回复S6F12 - Event Report Acknowledge
                        // 0x00 = 接受事件报告
                        sendToClient(sourceAddress, 6, 12, false, Secs2.binary((byte)0x00));
                        logger.info("发送S6F12回复到客户端: " + sourceAddress + ", 接受事件报告");
                    } catch (Exception e) {
                        // 事件报告数据格式错误
                        // 0x01 = 拒绝事件报告
                        sendToClient(sourceAddress, 6, 12, false, Secs2.binary((byte)0x01));
                        logger.warning("发送S6F12回复到客户端: " + sourceAddress + ", 拒绝事件报告 - 格式错误");
                    }
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S6F0响应
                    sendSxF0Response(message, sourceAddress);
                }
            }
        }
    }

    /**
     * 发送SxF0响应
     *
     * @param message 原始消息
     * @param sourceAddress 目标地址
     * @throws InterruptedException 如果线程被中断
     * @throws SecsException 如果发送消息失败
     */
    private void sendSxF0Response(SecsMessage message, SocketAddress sourceAddress)
            throws InterruptedException, SecsException {
        int stream = message.getStream();
        sendToClient(sourceAddress, stream, 0, false, Secs2.binary((byte)0x00));
        logger.info(String.format("发送S%dF0响应到客户端: %s", stream, sourceAddress));
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
        System.out.println(String.format("准备发送S%dF%d%s消息到客户端: %s",
                stream, function, (wbit ? " W" : ""), clientAddress));

        // 获取客户端连接
        ClientConnection connection = connectionManager.getConnection(clientAddress);

        if (connection == null) {
            System.out.println("找不到客户端连接: " + clientAddress);
            logger.warning("找不到客户端连接: " + clientAddress);
            throw new SecsSendMessageException("Client connection not found: " + clientAddress);
        }

        if (connection.isClosed()) {
            System.out.println("客户端连接已关闭: " + clientAddress);
            logger.warning("客户端连接已关闭: " + clientAddress);
            throw new SecsSendMessageException("Client connection is closed: " + clientAddress);
        }

        // 检查通信器状态
        System.out.println("检查通信器状态: " +
                (connection.getCommunicator().isOpen() ? "已打开" : "未打开"));

        // 发送消息
        try {
            System.out.println(String.format("开始发送S%dF%d%s消息到客户端: %s",
                    stream, function, (wbit ? " W" : ""), clientAddress));

            Optional<SecsMessage> result = connection.send(stream, function, wbit, secs2);

            System.out.println(String.format("消息S%dF%d%s已发送到客户端: %s",
                    stream, function, (wbit ? " W" : ""), clientAddress));

            return result;
        } catch (Exception e) {
            System.out.println(String.format("发送S%dF%d%s消息失败: %s",
                    stream, function, (wbit ? " W" : ""), e.getMessage()));
            e.printStackTrace();
            throw e;
        }
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
}
