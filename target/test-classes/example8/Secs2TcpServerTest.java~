package example8;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs1ontcpip.ext.ExtendedSecs1OnTcpIpReceiverCommunicator;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;

/**
 * SECS-I TCP服务器测试类
 *
 * 这个类演示了如何使用secs4java8库创建SECS-I TCP服务器，
 * 接收客户端消息并发送响应。
 *
 * 主要功能：
 * 1. 创建并配置SECS-I TCP服务器
 * 2. 接收并处理客户端的各种SECS消息
 * 3. 发送响应和主动请求
 */
public class Secs1TcpServerTest {

    private static final Logger logger = Logger.getLogger(Secs1TcpServerTest.class.getName());

    // 配置参数
    private static final String SERVER_IP = "127.0.0.1";  // 服务器IP地址
    private static final int SERVER_PORT = 5000;          // 服务器端口
    private static final int DEVICE_ID = 0;              // 设备ID
    private static final boolean IS_EQUIP = false;        // 是否为设备端
    private static final int TIMEOUT_SECONDS = 10;        // 超时时间(秒)
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30; // 心跳间隔时间(秒)
    private static final int CLIENT_INFO_INTERVAL_SECONDS = 60; // 客户端信息打印间隔时间(秒)

    // 线程
    private Thread heartbeatThread;
    private Thread clientInfoThread;

    // 客户端连接信息
    private final Map<SocketAddress, ClientInfo> clientChannels = new ConcurrentHashMap<>();

    /**
     * 客户端信息类，存储客户端地址和专用的通信器
     */
    private static class ClientInfo {
        private final SocketAddress address;
        private final SecsCommunicator communicator;
        private final int clientId;
        private final long connectTime;

        public ClientInfo(SocketAddress address, SecsCommunicator communicator, int clientId) {
            this.address = address;
            this.communicator = communicator;
            this.clientId = clientId;
            this.connectTime = System.currentTimeMillis();
        }

        public SocketAddress getAddress() {
            return address;
        }

        public SecsCommunicator getCommunicator() {
            return communicator;
        }

        public int getClientId() {
            return clientId;
        }

        public long getConnectTime() {
            return connectTime;
        }

        public String getConnectTimeString() {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(connectTime));
        }
    }

    // 通信器实例
    private final SecsCommunicator communicator;

    // 扩展的通信器实例，用于向特定客户端发送消息
    // 注意：我们不再使用扩展的通信器，而是使用原始的通信器
    private final SecsCommunicator mainCommunicator;

    // 客户端计数器，用于生成客户端ID
    private final AtomicInteger clientIdCounter = new AtomicInteger(1);

    /**
     * 构造函数
     *
     * @throws IOException 如果创建通信器失败
     */
    public Secs1TcpServerTest() throws IOException, SecsSendMessageException {
        // 创建SECS-I接收器配置
        Secs1OnTcpIpReceiverCommunicatorConfig config = new Secs1OnTcpIpReceiverCommunicatorConfig();

        // 设置服务器地址和端口
        config.socketAddress(new InetSocketAddress(SERVER_IP, SERVER_PORT));

        // 设置设备ID
        config.deviceId(DEVICE_ID);

        // 设置为被动方(Slave)
        config.isMaster(false);

        // 设置为主机端
        config.isEquip(IS_EQUIP);

        // 设置超时参数 - 优化多客户端连接的超时时间
        config.timeout().t1(1.0F);     // ENQ超时 - 减少以加快响应
        config.timeout().t2(45.0F);    // 回复超时 - 增加以处理多客户端
        config.timeout().t3(90.0F);    // 发送超时 - 增加以处理大消息
        config.timeout().t4(90.0F);    // 中间响应超时 - 增加以处理复杂响应

        // 设置重试次数 - 优化多客户端连接的重试策略
        config.retry(3);  // 减少重试次数以避免过多重试导致的阻塞

        // 尝试设置其他参数来支持多客户端
        try {
            // 尝试设置连接模式
            java.lang.reflect.Method connectionModeMethod = config.getClass().getMethod("connectionMode", Object.class);
            connectionModeMethod.invoke(config, "PASSIVE");
            logger.info("设置连接模式为PASSIVE成功");

            // 尝试设置最大连接数
            try {
                java.lang.reflect.Method maxConnectionsMethod = config.getClass().getMethod("maxConnections", int.class);
                maxConnectionsMethod.invoke(config, 10); // 允许10个客户端同时连接
                logger.info("设置最大连接数为10成功");
            } catch (NoSuchMethodException e) {
                logger.info("无法设置最大连接数，使用默认值");
            }

            // 尝试设置缓冲区大小
            try {
                java.lang.reflect.Method bufferSizeMethod = config.getClass().getMethod("bufferSize", int.class);
                bufferSizeMethod.invoke(config, 65536); // 设置更大的缓冲区
                logger.info("设置缓冲区大小为65536成功");
            } catch (NoSuchMethodException e) {
                logger.info("无法设置缓冲区大小，使用默认值");
            }
        } catch (Exception e) {
            // 其他异常
            logger.warning("设置高级参数失败: " + e.getMessage());
        }

        // 创建原始的通信器，用于接受客户端连接
        System.out.println("[DEBUG-INIT] 创建原始的通信器");
        this.communicator = Secs1OnTcpIpReceiverCommunicator.newInstance(config);

        // 使用同一个通信器实例
        this.mainCommunicator = this.communicator;

        // 设置日志级别为INFO
        Logger.getLogger("").setLevel(Level.INFO);

        // 添加消息接收监听器
        this.mainCommunicator.addSecsMessageReceiveListener(this::handleReceivedMessage);

        // 添加通信状态变化监听器
        this.mainCommunicator.addSecsCommunicatableStateChangeListener(this::handleCommunicationStateChange);

        // 添加日志监听器
        this.mainCommunicator.addSecsLogListener(log -> {
            String logMessage = log.toString();
            logger.info(logMessage);

            // 在日志中检测客户端连接事件
            try {
                if (logMessage.contains("Accepted") && logMessage.contains("SECS1-onTCP/IP-Communicator")) {
                    // 客户端连接事件
                    handleClientConnectionFromLog(logMessage, true);
                } else if (logMessage.contains("AcceptClosed") && logMessage.contains("SECS1-onTCP/IP-Communicator")) {
                    // 客户端断开连接事件
                    handleClientConnectionFromLog(logMessage, false);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "处理日志事件失败: " + logMessage, e);
            }
        });

    }

    /**
     * 打开连接
     *
     * @throws IOException 如果打开连接失败
     * @throws InterruptedException 如果线程被中断
     */
    public void open() throws IOException, InterruptedException {
        logger.info("正在打开SECS-I服务器...");
        this.communicator.open();
        logger.info("SECS-I服务器已打开，等待连接...");

        // 心跳线程暂时禁用
        // startHeartbeatThread();

        // 启动客户端信息打印线程
        startClientInfoThread();
    }

    /**
     * 关闭连接
     *
     * @throws IOException 如果关闭连接失败
     */
    public void close() throws IOException {
        try {
            // 心跳线程暂时禁用
            // stopHeartbeatThread();

            // 停止客户端信息打印线程
            stopClientInfoThread();

            // 关闭消息处理线程池
            try {
                // 尝试优雅关闭线程池
                messageProcessorPool.shutdown();
                if (!messageProcessorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    // 如果超时，强制关闭
                    messageProcessorPool.shutdownNow();
                }
            } catch (Exception e) {
                logger.warning("关闭消息处理线程池失败: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            // 忽略中断异常
            logger.warning("停止线程时被中断");
        }

        // 清空客户端连接信息
        clientChannels.clear();

        logger.info("正在关闭SECS-I服务器...");
        this.communicator.close();
        logger.info("SECS-I服务器已关闭");
    }

    /**
     * 处理接收到的消息
     * 使用线程池处理消息，避免阻塞主线程
     *
     * @param message 接收到的SECS消息
     */
    private void handleReceivedMessage(SecsMessage message) {
        // 使用线程池异步处理消息，避免阻塞主线程
        messageProcessorPool.submit(() -> {
            processReceivedMessage(message);
        });
    }

    /**
     * 处理接收到的消息的实际实现
     *
     * @param message 接收到的SECS消息
     */
    private void processReceivedMessage(SecsMessage message) {
        try {
            int stream = message.getStream();
            int function = message.getFunction();
            boolean wbit = message.wbit();

            System.out.println("[DEBUG-PROCESS] 开始处理消息: S" + stream + "F" + function + (wbit ? " W" : ""));
            System.out.println("[DEBUG-PROCESS] 当前客户端连接数: " + clientChannels.size());
            System.out.println("[DEBUG-PROCESS] 当前客户端列表: " + clientChannels.keySet());

            // 获取消息的标识符，用于跟踪消息来源
            // 注意：不同版本的secs4java8库可能有不同的API
            String messageId = "" + System.currentTimeMillis();
            try {
                // 尝试获取消息的唯一标识符
                messageId = message.toString();
            } catch (Exception e) {
                // 忽略异常，使用默认标识符
            }

            // 尝试获取消息的来源地址
            SocketAddress sourceAddr = null;
            try {
                // 尝试使用反射获取消息的来源地址
                java.lang.reflect.Field sourceAddrField = message.getClass().getDeclaredField("sourceAddr");
                if (sourceAddrField != null) {
                    sourceAddrField.setAccessible(true);
                    sourceAddr = (SocketAddress) sourceAddrField.get(message);
                    System.out.println("[DEBUG-PROCESS] 使用反射获取到消息来源地址: " + sourceAddr);
                }
            } catch (Exception e) {
                System.out.println("[DEBUG-PROCESS] 使用反射获取消息来源地址失败: " + e.getMessage());
            }

            logger.info(String.format("收到消息: S%dF%d%s ID=%s %s",
                    stream, function, (wbit ? " W" : ""), messageId, message.secs2()));

            // 添加错误处理和恢复机制
            try {
                // 根据消息类型处理
                switch (stream) {
                    case 1: {
                        // Stream 1: 设备信息和通信
                        handleStream1Message(message);
                        break;
                    }
                    case 2: {
                        // Stream 2: 设备控制和状态
                        handleStream2Message(message);
                        break;
                    }
                    case 5: {
                        // Stream 5: 异常和报警
                        handleStream5Message(message);
                        break;
                    }
                    case 6: {
                        // Stream 6: 数据采集
                        handleStream6Message(message);
                        break;
                    }
                    default: {
                        // 其他Stream
                        if (wbit) {
                            // 如果需要回复，发送通用的SxF0响应
                            sendSxF0Response(message);
                        }
                    }
                }
            } catch (Exception e) {
                // 处理消息长度错误或其他错误
                String errorMessage = e.getMessage();
                if (e.getClass().getName().contains("Secs1IllegalLengthByteException") ||
                    (errorMessage != null && errorMessage.contains("length"))) {
                    logger.log(Level.WARNING, "消息长度错误，忽略此消息: " + errorMessage, e);
                    // 不发送响应，因为消息格式错误
                } else {
                    logger.log(Level.WARNING, "处理消息失败: " + errorMessage, e);

                    // 如果需要回复且发生错误，尝试发送错误响应
                    if (wbit) {
                        try {
                            // 发送SxF0错误响应
                            this.communicator.send(message, stream, 0, false, Secs2.binary((byte)0x01));
                            logger.info(String.format("发送S%dF0错误响应", stream));
                        } catch (Exception ex) {
                            logger.log(Level.WARNING, "发送错误响应失败", ex);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 捕获所有异常，确保消息处理失败不会影响服务器
            logger.log(Level.SEVERE, "消息处理过程中发生严重错误", e);
        }
    }

    /**
     * 处理Stream 1消息
     */
    private void handleStream1Message(SecsMessage message) throws InterruptedException, SecsException {
        int function = message.getFunction();
        boolean wbit = message.wbit();

        switch (function) {
            case 1: {
                // S1F1 W - Are You There
                if (wbit) {
                    // 回复S1F2 - On Line Data
                    Secs2 reply = Secs2.list(
                        Secs2.ascii("SERVER-A"),  // 设备型号
                        Secs2.ascii("000001")     // 软件版本
                    );

                    this.communicator.send(message, 1, 2, false, reply);
                    logger.info("发送S1F2回复: " + reply);
                }
                break;
            }
            case 13: {
                // S1F13 W - Establish Communications Request
                if (wbit) {
                    // 回复S1F14 - Establish Communications Request Acknowledge
                    // 0x00 = 通信已建立
                    this.communicator.send(message, 1, 14, false, Secs2.binary((byte)0x00));
                    logger.info("发送S1F14回复: 通信已建立");
                }
                break;
            }
            case 15: {
                // S1F15 W - Request OFF-LINE
                if (wbit) {
                    // 回复S1F16 - OFF-LINE Acknowledge
                    // 0x00 = 接受请求
                    this.communicator.send(message, 1, 16, false, Secs2.binary((byte)0x00));
                    logger.info("发送S1F16回复: 接受离线请求");
                }
                break;
            }
            case 17: {
                // S1F17 W - Request ON-LINE
                if (wbit) {
                    // 回复S1F18 - ON-LINE Acknowledge
                    // 0x00 = 接受请求
                    this.communicator.send(message, 1, 18, false, Secs2.binary((byte)0x00));
                    logger.info("发送S1F18回复: 接受在线请求");
                }
                break;
            }
            case 3: {
                // S1F3 W - Selected Equipment Status Request
                if (wbit) {
                    try {
                        System.out.println("[DEBUG-S1F3] 开始处理S1F3请求");

                        // 解析请求的状态ID列表
                        Secs2 data = message.secs2();
                        System.out.println("[DEBUG-S1F3] 请求数据: " + data);
                        logger.info("收到S1F3请求，状态ID列表: " + data);

                        // 创建状态数据响应
                        // 注意：这里我们只是创建一些示例数据
                        // 实际应用中应该根据请求的状态ID返回相应的数据

                        // 创建一个包含多个状态数据的列表
                        Secs2 statusList = Secs2.list(
                            Secs2.ascii("READY"),       // 设备状态
                            Secs2.binary((byte)0x00),  // 警告状态
                            Secs2.binary((byte)0x00),  // 错误状态
                            Secs2.uint4(100001),       // 设备ID
                            Secs2.ascii("SERVER-A"),    // 设备名称
                            Secs2.ascii("ONLINE")       // 连接状态
                        );

                        System.out.println("[DEBUG-S1F3] 创建的状态列表: " + statusList);

                        // 获取消息的来源地址
                        SocketAddress sourceAddr = null;
                        try {
                            // 尝试使用反射获取消息的来源地址
                            java.lang.reflect.Field sourceAddrField = message.getClass().getDeclaredField("sourceAddr");
                            if (sourceAddrField != null) {
                                sourceAddrField.setAccessible(true);
                                sourceAddr = (SocketAddress) sourceAddrField.get(message);
                                System.out.println("[DEBUG-S1F3] 消息来源地址: " + sourceAddr);
                            }
                        } catch (Exception e) {
                            System.out.println("[DEBUG-S1F3] 获取消息来源地址失败: " + e.getMessage());
                        }

                        // 如果有来源地址，使用客户端的通信器发送回复
                        if (sourceAddr != null) {
                            ClientInfo clientInfo = clientChannels.get(sourceAddr);
                            if (clientInfo != null) {
                                System.out.println("[DEBUG-S1F3] 找到客户端信息，客户端ID: " + clientInfo.getClientId());

                                // 在消息中添加客户端ID作为标识
                                Secs2 statusListWithClientId = Secs2.list(
                                    Secs2.uint4(clientInfo.getClientId()), // 客户端ID作为标识
                                    statusList                             // 原始状态列表
                                );

                                System.out.println("[DEBUG-S1F3] 添加客户端ID后的状态列表: " + statusListWithClientId);

                                // 使用主通信器发送回复
                                this.communicator.send(1, 4, false, statusListWithClientId);
                                System.out.println("[DEBUG-S1F3] 回复发送成功");
                            } else {
                                System.out.println("[DEBUG-S1F3] 找不到客户端信息，使用原始回复");
                                this.communicator.send(message, 1, 4, false, statusList);
                            }
                        } else {
                            System.out.println("[DEBUG-S1F3] 没有来源地址，使用原始回复");
                            this.communicator.send(message, 1, 4, false, statusList);
                        }

                        logger.info("发送S1F4回复: " + statusList);
                    } catch (Exception e) {
                        System.out.println("[DEBUG-S1F3] 处理S1F3请求失败: " + e.getMessage());
                        e.printStackTrace(System.out);
                        logger.log(Level.WARNING, "处理S1F3请求失败", e);
                        // 如果处理失败，发送空列表响应
                        this.communicator.send(message, 1, 4, false, Secs2.list());
                    }
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S1F0响应
                    sendSxF0Response(message);
                }
            }
        }
    }

    /**
     * 处理Stream 2消息
     */
    private void handleStream2Message(SecsMessage message) throws InterruptedException, SecsException {
        int function = message.getFunction();
        boolean wbit = message.wbit();

        switch (function) {
            case 21: {
                // S2F21 W - Remote Command
                if (wbit) {
                    try {
                        // 解析命令
                        String command = message.secs2().getAscii();
                        logger.info("收到远程命令: " + command);

                        // 回复S2F22 - Remote Command Acknowledge
                        // 0x00 = 接受命令
                        this.communicator.send(message, 2, 22, false, Secs2.binary((byte)0x00));
                        logger.info("发送S2F22回复: 接受远程命令");
                    } catch (Secs2Exception e) {
                        // 命令格式错误
                        // 0x01 = 拒绝命令
                        this.communicator.send(message, 2, 22, false, Secs2.binary((byte)0x01));
                        logger.warning("发送S2F22回复: 拒绝远程命令 - 格式错误");
                    }
                }
                break;
            }
            case 31: {
                // S2F31 W - Date and Time Set Request
                if (wbit) {
                    try {
                        // 解析日期时间
                        String dateTime = message.secs2().getAscii();
                        logger.info("收到日期时间设置请求: " + dateTime);

                        // 回复S2F32 - Date and Time Set Acknowledge
                        // 0x00 = 接受请求
                        this.communicator.send(message, 2, 32, false, Secs2.binary((byte)0x00));
                        logger.info("发送S2F32回复: 接受日期时间设置请求");
                    } catch (Secs2Exception e) {
                        // 日期时间格式错误
                        // 0x01 = 拒绝请求
                        this.communicator.send(message, 2, 32, false, Secs2.binary((byte)0x01));
                        logger.warning("发送S2F32回复: 拒绝日期时间设置请求 - 格式错误");
                    }
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S2F0响应
                    sendSxF0Response(message);
                }
            }
        }
    }

    /**
     * 处理Stream 5消息
     */
    private void handleStream5Message(SecsMessage message) throws InterruptedException, SecsException {
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

                        logger.info(String.format("收到报警报告: ID=%d, Text=%s", alarmId, alarmText));

                        // 回复S5F2 - Alarm Report Acknowledge
                        // 0x00 = 接受报警
                        this.communicator.send(message, 5, 2, false, Secs2.binary((byte)0x00));
                        logger.info("发送S5F2回复: 接受报警报告");
                    } catch (Exception e) {
                        // 报警数据格式错误
                        // 0x01 = 拒绝报警
                        this.communicator.send(message, 5, 2, false, Secs2.binary((byte)0x01));
                        logger.warning("发送S5F2回复: 拒绝报警报告 - 格式错误");
                    }
                }
                break;
            }
            case 5: {
                // S5F5 W - LinkTest.Request (心跳请求)
                if (wbit) {
                    logger.info("收到心跳请求: S5F5 LinkTest.Request");

                    // 回复S5F6 - LinkTest.Acknowledge (心跳响应)
                    // 通常是空列表
                    this.communicator.send(message, 5, 6, false, Secs2.list());
                    logger.info("发送心跳响应: S5F6 LinkTest.Acknowledge");
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S5F0响应
                    sendSxF0Response(message);
                }
            }
        }
    }

    /**
     * 处理Stream 6消息
     */
    private void handleStream6Message(SecsMessage message) throws InterruptedException, SecsException {
        int function = message.getFunction();
        boolean wbit = message.wbit();

        switch (function) {
            case 1: {
                // S6F1 W - Trace Data Request
                if (wbit) {
                    logger.info("收到S6F1请求: 跟踪数据请求");

                    // 创建一些示例跟踪数据
                    Secs2 traceData = Secs2.list(
                        Secs2.ascii("TRACE_DATA"),
                        Secs2.uint4(System.currentTimeMillis() / 1000),
                        Secs2.list(
                            Secs2.uint4(1),
                            Secs2.uint4(2),
                            Secs2.uint4(3)
                        )
                    );

                    // 回复S6F2 - Trace Data
                    this.communicator.send(message, 6, 2, false, traceData);
                    logger.info("发送S6F2回复: 跟踪数据");
                }
                break;
            }
            case 11: {
                // S6F11 W - Event Report Send
                if (wbit) {
                    try {
                        // 解析事件报告数据
                        Secs2 data = message.secs2();
                        int eventId = data.get(0).getInt();
                        int reportId = data.get(1).getInt();
                        Secs2 reportData = data.get(2);

                        logger.info(String.format("收到事件报告: EventID=%d, ReportID=%d, Data=%s",
                                eventId, reportId, reportData));

                        // 回复S6F12 - Event Report Acknowledge
                        // 0x00 = 接受事件报告
                        this.communicator.send(message, 6, 12, false, Secs2.binary((byte)0x00));
                        logger.info("发送S6F12回复: 接受事件报告");
                    } catch (Exception e) {
                        // 事件报告数据格式错误
                        // 0x01 = 拒绝事件报告
                        this.communicator.send(message, 6, 12, false, Secs2.binary((byte)0x01));
                        logger.warning("发送S6F12回复: 拒绝事件报告 - 格式错误");
                    }
                }
                break;
            }
            default: {
                // 其他Function
                if (wbit) {
                    // 如果需要回复，发送S6F0响应
                    sendSxF0Response(message);
                }
            }
        }
    }

    /**
     * 发送通用的SxF0响应（未知函数响应）
     */
    private void sendSxF0Response(SecsMessage message) throws InterruptedException, SecsException {
        int stream = message.getStream();

        // 0x01 = 未知函数
        this.communicator.send(message, stream, 0, false, Secs2.binary((byte)0x01));
        logger.info(String.format("发送S%dF0回复: 未知函数", stream));
    }

    /**
     * 处理通信状态变化
     */
    private void handleCommunicationStateChange(boolean communicatable) {
        logger.info("通信状态变更为: " + (communicatable ? "可通信" : "不可通信"));

        // 服务器端不需要在通信状态变化时执行特定操作
    }

    /**
     * 发送S1F3 Selected Equipment Status Request
     *
     * @return 如果收到回复则返回true，否则返回false
     */
    public boolean sendS1F3StatusRequest() {
        try {
            logger.info("发送S1F3: Selected Equipment Status Request");

            // 创建状态列表请求
            // <L [2]
            //   <U4 1> // SVID 1: 设备状态
            //   <U4 2> // SVID 2: 警告状态
            // >
            Secs2 data = Secs2.list(
                Secs2.uint4(1),
                Secs2.uint4(2)
            );

            Optional<SecsMessage> reply = this.communicator.send(1, 3, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                logger.info(String.format("收到S1F4回复: %s", msg.secs2()));
                return true;
            } else {
                logger.warning("未收到S1F4回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S1F3失败", e);
            return false;
        }
    }

    /**
     * 发送S2F13 Equipment Constant Request
     *
     * @return 如果收到回复则返回true，否则返回false
     */
    public boolean sendS2F13ConstantRequest() {
        try {
            logger.info("发送S2F13: Equipment Constant Request");

            // 创建常量列表请求
            // <L [2]
            //   <U4 1> // ECID 1: 超时设置
            //   <U4 2> // ECID 2: 重试次数
            // >
            Secs2 data = Secs2.list(
                Secs2.uint4(1),
                Secs2.uint4(2)
            );

            Optional<SecsMessage> reply = this.communicator.send(2, 13, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                logger.info(String.format("收到S2F14回复: %s", msg.secs2()));
                return true;
            } else {
                logger.warning("未收到S2F14回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S2F13失败", e);
            return false;
        }
    }

    /**
     * 发送S2F33 Define Report
     *
     * @return 如果收到回复则返回true，否则返回false
     */
    public boolean sendS2F33DefineReport() {
        try {
            logger.info("发送S2F33: Define Report");

            // 创建报告定义
            // <L [1]
            //   <L [2]
            //     <U4 1> // RPTID 1: 状态报告
            //     <L [2]
            //       <U4 1> // VID 1: 设备状态
            //       <U4 2> // VID 2: 警告状态
            //     >
            //   >
            // >
            Secs2 data = Secs2.list(
                Secs2.list(
                    Secs2.uint4(1),
                    Secs2.list(
                        Secs2.uint4(1),
                        Secs2.uint4(2)
                    )
                )
            );

            Optional<SecsMessage> reply = this.communicator.send(2, 33, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] replyData = msg.secs2().getBytes();

                if (replyData.length > 0 && replyData[0] == 0x00) {
                    logger.info("报告定义成功");
                    return true;
                } else {
                    logger.warning("报告定义被拒绝");
                    return false;
                }
            } else {
                logger.warning("未收到S2F34回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S2F33失败", e);
            return false;
        }
    }

    /**
     * 发送S2F35 Link Event Report
     *
     * @return 如果收到回复则返回true，否则返回false
     */
    public boolean sendS2F35LinkEventReport() {
        try {
            logger.info("发送S2F35: Link Event Report");

            // 创建事件报告链接
            // <L [2]
            //   <U4 1> // CEID 1: 设备状态变化
            //   <L [1]
            //     <U4 1> // RPTID 1: 状态报告
            //   >
            // >
            Secs2 data = Secs2.list(
                Secs2.list(
                    Secs2.uint4(1),
                    Secs2.list(
                        Secs2.uint4(1)
                    )
                )
            );

            Optional<SecsMessage> reply = this.communicator.send(2, 35, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] replyData = msg.secs2().getBytes();

                if (replyData.length > 0 && replyData[0] == 0x00) {
                    logger.info("事件报告链接成功");
                    return true;
                } else {
                    logger.warning("事件报告链接被拒绝");
                    return false;
                }
            } else {
                logger.warning("未收到S2F36回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S2F35失败", e);
            return false;
        }
    }

    /**
     * 发送S2F37 Enable/Disable Event Report
     *
     * @param enable 是否启用事件报告
     * @return 如果收到回复则返回true，否则返回false
     */
    public boolean sendS2F37EnableEventReport(boolean enable) {
        try {
            logger.info("发送S2F37: " + (enable ? "Enable" : "Disable") + " Event Report");

            // 创建启用/禁用事件报告请求
            // <L [2]
            //   <BOOL enable>  // true=启用, false=禁用
            //   <L [1]         // 事件ID列表
            //     <U4 101>     // CEID 101: 设备启动事件
            //   >
            // >
            Secs2 data = Secs2.list(
                Secs2.bool(enable),
                Secs2.list(
                    Secs2.uint4(101)
                )
            );

            Optional<SecsMessage> reply = this.communicator.send(2, 37, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] replyData = msg.secs2().getBytes();

                if (replyData.length > 0 && replyData[0] == 0x00) {
                    logger.info("事件报告" + (enable ? "启用" : "禁用") + "成功");
                    return true;
                } else {
                    logger.warning("事件报告" + (enable ? "启用" : "禁用") + "被拒绝");
                    return false;
                }
            } else {
                logger.warning("未收到S2F38回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S2F37失败", e);
            return false;
        }
    }

    /**
     * 发送自定义SECS消息
     *
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param data 消息数据
     * @return 如果wbit为true，则返回回复消息；否则返回空
     */
    public Optional<SecsMessage> sendCustomMessage(int stream, int function, boolean wbit, Secs2 data) {
        try {
            logger.info(String.format("发送自定义消息: S%dF%d%s %s",
                    stream, function, (wbit ? " W" : ""), data));

            return this.communicator.send(stream, function, wbit, data);
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送自定义消息失败", e);
            return Optional.empty();
        }
    }

    // 线程池，用于处理客户端请求
    private static final java.util.concurrent.ExecutorService messageProcessorPool =
            java.util.concurrent.Executors.newFixedThreadPool(10);

    /**
     * 主方法，用于启动服务器
     */
    public static void main(String[] args) {
        try {
            // 创建SECS-I服务器
            Secs1TcpServerTest server = new Secs1TcpServerTest();

            // 添加关闭钩子，确保程序退出时关闭连接
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("\n正在关闭服务器...");
                    server.close();
                    System.out.println("服务器已安全关闭");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));

            try {
                // 打开连接
                server.open();
                System.out.println("SECS-I TCP服务器已启动，监听地址: " + SERVER_IP + ":" + SERVER_PORT);
                System.out.println("服务器将持续运行，等待客户端连接...");
                System.out.println("按Ctrl+C终止服务器");

                // 创建命令行交互线程
                Thread commandThread = new Thread(() -> {
                    try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
                        while (true) {
                            System.out.print("\n输入命令 (help 查看帮助): ");
                            String command = scanner.nextLine().trim();

                            if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit")) {
                                System.out.println("正在退出...");
                                System.exit(0);
                            } else if (command.equalsIgnoreCase("help")) {
                                printHelp();
                            } else if (command.equalsIgnoreCase("status")) {
                                System.out.println("服务器状态: 运行中");
                                System.out.println("监听地址: " + SERVER_IP + ":" + SERVER_PORT);
                                System.out.println("当前连接客户端数: " + server.getClientCount());
                            } else if (command.equalsIgnoreCase("clients")) {
                                server.printClientInfo();
                            } else if (command.equalsIgnoreCase("test")) {
                                runTests(server);
                            } else if (command.equalsIgnoreCase("heartbeat")) {
                                System.out.println("心跳功能已禁用");
                                // try {
                                //     server.sendHeartbeat();
                                //     System.out.println("已手动发送心跳");
                                // } catch (Exception e) {
                                //     System.out.println("发送心跳失败: " + e.getMessage());
                                // }
                            } else if (command.startsWith("send ")) {
                                try {
                                    String[] parts = command.split(" ", 5);
                                    if (parts.length < 5) {
                                        System.out.println("命令格式错误: send <index> <stream> <function> <message>");
                                    } else {
                                        int index = Integer.parseInt(parts[1]);
                                        int stream = Integer.parseInt(parts[2]);
                                        int function = Integer.parseInt(parts[3]);
                                        String message = parts[4];

                                        boolean result = server.sendMessageToClient(index, stream, function, message);
                                        if (result) {
                                            System.out.println("消息发送成功");
                                        } else {
                                            System.out.println("消息发送失败，客户端不存在或索引超出范围");
                                        }
                                    }
                                } catch (NumberFormatException e) {
                                    System.out.println("参数格式错误: " + e.getMessage());
                                } catch (Exception e) {
                                    System.out.println("发送消息失败: " + e.getMessage());
                                }
                            } else if (!command.isEmpty()) {
                                System.out.println("未知命令: " + command);
                                System.out.println("输入 'help' 查看可用命令");
                            }
                        }
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "命令行处理异常", e);
                    }
                });
                commandThread.setDaemon(true);
                commandThread.start();

                // 主线程等待，保持服务器运行
                while (true) {
                    Thread.sleep(Long.MAX_VALUE);
                }

            } catch (InterruptedException e) {
                // 线程被中断，可能是程序正在退出
                logger.info("服务器线程被中断");
            } finally {
                // 关闭连接
                server.close();
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "服务器启动失败", e);
        }
    }

    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("\n可用命令:");
        System.out.println("  help     - 显示帮助信息");
        System.out.println("  status   - 显示服务器状态");
        System.out.println("  clients  - 显示当前连接的客户端");
        System.out.println("  test     - 运行测试序列");
        System.out.println("  heartbeat - [已禁用] 手动发送一次心跳");
        System.out.println("  send <index> <stream> <function> <message> - 向指定客户端发送SECS消息");
        System.out.println("  exit     - 退出程序");
    }

    /**
     * 运行测试序列
     */
    private static void runTests(Secs1TcpServerTest server) {
        System.out.println("\n开始运行测试序列...");

        try {
            // 发送S1F3 Status Request
            boolean s1f3Result = server.sendS1F3StatusRequest();
            System.out.println("S1F3测试结果: " + (s1f3Result ? "成功" : "失败"));

            // 发送S2F13 Constant Request
            boolean s2f13Result = server.sendS2F13ConstantRequest();
            System.out.println("S2F13测试结果: " + (s2f13Result ? "成功" : "失败"));

            // 发送S2F33 Define Report
            boolean s2f33Result = server.sendS2F33DefineReport();
            System.out.println("S2F33测试结果: " + (s2f33Result ? "成功" : "失败"));

            // 发送S2F35 Link Event Report
            boolean s2f35Result = server.sendS2F35LinkEventReport();
            System.out.println("S2F35测试结果: " + (s2f35Result ? "成功" : "失败"));

            // 发送S2F37 Enable Event Report
            boolean s2f37Result = server.sendS2F37EnableEventReport(true);
            System.out.println("S2F37测试结果: " + (s2f37Result ? "成功" : "失败"));

            // 发送自定义消息
            Secs2 customData = Secs2.list(
                    Secs2.ascii("SERVER_CUSTOM"),
                    Secs2.uint4(888)
            );
            Optional<SecsMessage> customReply = server.sendCustomMessage(9, 1, true, customData);
            System.out.println("自定义消息测试结果: " + (customReply.isPresent() ? "成功" : "失败"));

            System.out.println("测试序列完成\n");
        } catch (Exception e) {
            System.out.println("测试过程中发生异常: " + e.getMessage());
            e.printStackTrace();
        }


    }

    /**
     * 从日志消息中处理客户端连接事件
     *
     * @param logMessage 日志消息
     * @param isConnected true表示连接事件，false表示断开连接事件
     */
    private void handleClientConnectionFromLog(String logMessage, boolean isConnected) {
        try {
            // 从日志消息中提取客户端地址
            // 日志格式示例: "{"state":"Accepted","local":"/127.0.0.1:5000","remote":"/127.0.0.1:51332"}"
            // 或者: "SECS1-onTCP/IP-Communicator Accepted, local:/127.0.0.1:5000, remote:/127.0.0.1:49876"
            String remoteAddrStr = null;

            // 尝试解析JSON格式
            if (logMessage.contains("{\"state\":") && logMessage.contains("\"remote\":")) {
                int remoteStartIndex = logMessage.indexOf("\"remote\":") + 10; // "remote":" 长度为10
                int remoteEndIndex = logMessage.indexOf("\"", remoteStartIndex);
                if (remoteStartIndex > 0 && remoteEndIndex > remoteStartIndex) {
                    remoteAddrStr = logMessage.substring(remoteStartIndex, remoteEndIndex);
                }
            }
            // 尝试解析文本格式
            else if (logMessage.contains("remote:")) {
                int remoteIndex = logMessage.indexOf("remote:");
                if (remoteIndex > 0) {
                    // 提取远程地址部分
                    remoteAddrStr = logMessage.substring(remoteIndex + 7).trim();
                    // 如果有多余的内容，只取第一部分
                    if (remoteAddrStr.contains(" ")) {
                        remoteAddrStr = remoteAddrStr.substring(0, remoteAddrStr.indexOf(" "));
                    }
                    // 如果有结束的引号或花括号
                    if (remoteAddrStr.contains("\"") || remoteAddrStr.contains("}")) {
                        remoteAddrStr = remoteAddrStr.replaceAll("[\"{}]", "");
                    }
                }
            }

            if (remoteAddrStr != null) {
                // 创建客户端地址对象
                // 格式可能是类似于 "/127.0.0.1:49876"
                if (remoteAddrStr.startsWith("/")) {
                    remoteAddrStr = remoteAddrStr.substring(1);
                }

                // 分离 IP 和端口
                String[] parts = remoteAddrStr.split(":");
                if (parts.length == 2) {
                    String ip = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    InetSocketAddress clientAddr = new InetSocketAddress(ip, port);

                    if (isConnected) {
                        // 客户端连接事件
                        // 为新客户端创建一个客户端ID
                        int clientId = clientIdCounter.getAndIncrement();

                        // 不再尝试为每个客户端创建单独的通信器
                        // 而是使用主通信器，并在客户端信息中记录客户端地址
                        System.out.println("[DEBUG-CONNECTION] 为客户端 " + clientAddr + " 创建客户端信息");

                        // 创建客户端信息，使用主通信器
                        ClientInfo clientInfo = new ClientInfo(clientAddr, this.communicator, clientId);
                        clientChannels.put(clientAddr, clientInfo);

                        System.out.println("[DEBUG-CONNECTION] 客户端信息创建成功，当前客户端数: " + clientChannels.size());
                        System.out.println("[DEBUG-CONNECTION] 当前客户端列表: " + clientChannels.keySet());

                        logger.info(String.format("新客户端连接: ID=%d, 地址=%s, 当前连接数: %d",
                                clientId, clientAddr, clientChannels.size()));
                    } else {
                        // 客户端断开连接事件
                        ClientInfo removedClient = clientChannels.remove(clientAddr);
                        if (removedClient != null) {
                            logger.info(String.format("客户端断开连接: ID=%d, 地址=%s, 当前连接数: %d",
                                    removedClient.getClientId(), clientAddr, clientChannels.size()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "从日志消息处理客户端连接失败: " + logMessage, e);
        }
    }

    /**
     * 启动心跳线程
     *
     * @throws InterruptedException 如果线程被中断
     */
    private void startHeartbeatThread() throws InterruptedException {
        // 如果已经有心跳线程在运行，先停止它
        stopHeartbeatThread();

        // 创建新的心跳线程
        heartbeatThread = new Thread(() -> {
            logger.info("心跳线程已启动，间隔: " + HEARTBEAT_INTERVAL_SECONDS + "秒");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 等待指定的间隔时间
                    Thread.sleep(HEARTBEAT_INTERVAL_SECONDS * 1000);

                    try {
                        // 发送心跳
                        sendHeartbeat();
                    } catch (SecsException e) {
                        logger.log(Level.WARNING, "心跳发送失败", e);
                    }
                }
            } catch (InterruptedException e) {
                // 线程被中断，正常退出
                logger.info("心跳线程已停止");
            } catch (Exception e) {
                logger.log(Level.WARNING, "心跳线程异常", e);
            }
        });

        // 设置为守护线程，这样主程序退出时它会自动结束
        heartbeatThread.setDaemon(true);
        heartbeatThread.setName("SECS-Heartbeat");
        heartbeatThread.start();
    }

    /**
     * 停止心跳线程
     *
     * @throws InterruptedException 如果线程被中断
     */
    private void stopHeartbeatThread() throws InterruptedException {
        if (heartbeatThread != null && heartbeatThread.isAlive()) {
            heartbeatThread.interrupt();
            try {
                // 等待线程结束，但最多等待1秒
                heartbeatThread.join(1000);
            } catch (InterruptedException e) {
                // 忽略中断异常
            }
            heartbeatThread = null;
        }
    }

    /**
     * 启动客户端信息打印线程
     *
     * @throws InterruptedException 如果线程被中断
     */
    private void startClientInfoThread() throws InterruptedException {
        // 如果已经有客户端信息打印线程在运行，先停止它
        stopClientInfoThread();

        // 创建新的客户端信息打印线程
        clientInfoThread = new Thread(() -> {
            logger.info("客户端信息打印线程已启动，间隔: " + CLIENT_INFO_INTERVAL_SECONDS + "秒");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 等待指定的间隔时间
                    Thread.sleep(CLIENT_INFO_INTERVAL_SECONDS * 1000);

                    // 打印客户端信息
                    int count = clientChannels.size();
                    if (count > 0) {
                        logger.info("当前连接的客户端数: " + count);
                        int index = 0;
                        for (Map.Entry<SocketAddress, ClientInfo> entry : clientChannels.entrySet()) {
                            logger.info(String.format("  [%d] %s", index++, entry.getKey()));
                        }
                    } else {
                        logger.info("当前没有客户端连接");
                    }
                }
            } catch (InterruptedException e) {
                // 线程被中断，正常退出
                logger.info("客户端信息打印线程已停止");
            } catch (Exception e) {
                logger.log(Level.WARNING, "客户端信息打印线程异常", e);
            }
        });

        // 设置为守护线程，这样主程序退出时它会自动结束
        clientInfoThread.setDaemon(true);
        clientInfoThread.setName("SECS-ClientInfo");
        clientInfoThread.start();
    }

    /**
     * 停止客户端信息打印线程
     *
     * @throws InterruptedException 如果线程被中断
     */
    private void stopClientInfoThread() throws InterruptedException {
        if (clientInfoThread != null && clientInfoThread.isAlive()) {
            clientInfoThread.interrupt();
            try {
                // 等待线程结束，但最多等待1秒
                clientInfoThread.join(1000);
            } catch (InterruptedException e) {
                // 忽略中断异常
            }
            clientInfoThread = null;
        }
    }

    /**
     * 获取当前客户端数量
     *
     * @return 当前客户端数量
     */
    public int getClientCount() {
        return clientChannels.size();
    }

    /**
     * 打印当前客户端信息
     */
    public void printClientInfo() {
        // 先清理已断开的连接
        cleanupDisconnectedClients();

        int count = clientChannels.size();
        if (count > 0) {
            System.out.println("当前连接的客户端数: " + count);
            int index = 0;
            for (Map.Entry<SocketAddress, ClientInfo> entry : clientChannels.entrySet()) {
                ClientInfo clientInfo = entry.getValue();
                SocketAddress addr = clientInfo.getAddress();
                SecsCommunicator comm = clientInfo.getCommunicator();
                boolean isConnected = comm != null && !comm.isClosed();

                System.out.println(String.format("  [%d] ID=%d, 地址=%s, 连接时间=%s (状态: %s)",
                        index++, clientInfo.getClientId(), addr, clientInfo.getConnectTimeString(), isConnected ? "连接中" : "已断开"));
            }
        } else {
            System.out.println("当前没有客户端连接");
        }
    }

    /**
     * 发送心跳消息
     * 使用S5F5(LinkTest.Request)作为心跳消息
     *
     * @throws InterruptedException 如果线程被中断
     * @throws SecsException 如果发送SECS消息失败
     */
    public void sendHeartbeat() throws InterruptedException, SecsException {
        try {
            // 检查是否有客户端连接
            if (clientChannels.isEmpty()) {
                logger.info("没有客户端连接，跳过心跳发送");
                return;
            }

            logger.info("发送心跳消息 (S5F5 LinkTest.Request)...");

            // 创建S5F5消息数据，通常是空列表
            Secs2 data = Secs2.list();

            // 发送S5F5消息并等待S5F6回复
            Optional<SecsMessage> reply = this.communicator.send(5, 5, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                if (msg.getStream() == 5 && msg.getFunction() == 6) {
                    logger.info("收到心跳回复: S5F6 LinkTest.Acknowledge");
                } else {
                    logger.warning(String.format("收到非标准心跳回复: S%dF%d %s",
                            msg.getStream(), msg.getFunction(), msg.secs2()));
                }
            } else {
                logger.warning("未收到心跳回复，客户端可能已断开连接");
                // 清理可能已断开的连接
                cleanupDisconnectedClients();
            }
        } catch (SecsException e) {
            logger.log(Level.WARNING, "发送心跳失败", e);
            // 清理可能已断开的连接
            cleanupDisconnectedClients();
            throw e;
        }
    }

    /**
     * 清理已断开连接的客户端
     */
    private void cleanupDisconnectedClients() {
        // 尝试检测并移除已断开的客户端
        List<SocketAddress> disconnectedClients = new ArrayList<>();

        for (Map.Entry<SocketAddress, ClientInfo> entry : clientChannels.entrySet()) {
            SocketAddress addr = entry.getKey();
            ClientInfo info = entry.getValue();

            // 检查通信器是否已关闭
            if (info.getCommunicator() != null && info.getCommunicator().isClosed()) {
                disconnectedClients.add(addr);
            }
        }

        // 移除已断开的客户端
        for (SocketAddress addr : disconnectedClients) {
            clientChannels.remove(addr);
            logger.info("清理已断开的客户端: " + addr + ", 当前连接数: " + clientChannels.size());
        }
    }

    /**
     * 向指定客户端发送SECS消息
     *
     * @param index 客户端索引
     * @param stream 流ID
     * @param function 功能ID
     * @param messageText 消息文本
     * @return 如果发送成功返回true，否则返回false
     * @throws Exception 如果发送过程中发生异常
     */
    public boolean sendMessageToClient(int index, int stream, int function, String messageText) throws Exception {
        // 获取指定索引的客户端
        if (index < 0 || index >= clientChannels.size()) {
            return false;
        }

        // 获取客户端列表
        List<ClientInfo> clientList = new ArrayList<>(clientChannels.values());

        // 获取指定索引的客户端信息
        ClientInfo clientInfo = clientList.get(index);
        SocketAddress clientAddr = clientInfo.getAddress();

        // 创建SECS消息数据
        Secs2 data = Secs2.ascii(messageText);

        // 发送消息
        logger.info(String.format("向客户端 [%d] %s 发送消息: S%dF%d %s",
                index, clientAddr, stream, function, messageText));

        try {
            // 记录当前的目标客户端地址
            System.out.println("[DEBUG-SEND] 尝试向客户端发送消息: " + clientAddr);
            System.out.println("[DEBUG-SEND] 消息类型: S" + stream + "F" + function);
            System.out.println("[DEBUG-SEND] 消息数据: " + data);

            // 记录当前的客户端地址，以便于在消息接收器中识别
            // 这里我们使用一个特殊的标记来识别目标客户端
            // 在消息中添加客户端的ID作为标识
            Secs2 dataWithClientId = Secs2.list(
                Secs2.uint4(clientInfo.getClientId()), // 客户端ID作为标识
                data                                  // 原始消息数据
            );

            System.out.println("[DEBUG-SEND] 添加客户端ID后的消息数据: " + dataWithClientId);

            // 使用主通信器发送消息
            this.communicator.send(stream, function, false, dataWithClientId);
            System.out.println("[DEBUG-SEND] 消息发送成功");
        } catch (Exception e) {
            System.out.println("[DEBUG-SEND] 发送消息失败: " + e.getMessage());
            e.printStackTrace(System.out);
            logger.log(Level.WARNING, "向客户端发送消息失败: " + e.getMessage(), e);
            return false;
        }

        return true;
    }
}
