package example8;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpCommunicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;

/**
 * SECS-I TCP客户端测试类
 *
 * 这个类演示了如何使用secs4java8库创建SECS-I TCP客户端，
 * 发送各种类型的SECS消息并处理响应。
 *
 * 主要功能：
 * 1. 创建并配置SECS-I TCP客户端
 * 2. 发送S1F1 (Are You There)请求并处理S1F2响应
 * 3. 发送S1F13 (Establish Communications)请求并处理S1F14响应
 * 4. 发送S2F31 (Date and Time Set)请求并处理S2F32响应
 * 5. 发送S5F1 (Alarm Report)通知
 * 6. 接收并处理来自服务器的请求
 */
public class Secs1TcpClientTest {

    private static final Logger logger = Logger.getLogger(Secs1TcpClientTest.class.getName());

    // 配置参数
    private static final String SERVER_IP = "127.0.0.1";  // 服务器IP地址
    private static final int SERVER_PORT = 5000;          // 服务器端口
    private static final int DEVICE_ID = 10;              // 设备ID
    private static final boolean IS_EQUIP = true;         // 是否为设备端
    private static final int TIMEOUT_SECONDS = 10;        // 超时时间(秒)

    // 通信器实例
    private final SecsCommunicator communicator;

    /**
     * 构造函数
     *
     * @throws IOException 如果创建通信器失败
     */
    public Secs1TcpClientTest() throws IOException {
        // 创建SECS-I配置
        Secs1OnTcpIpCommunicatorConfig config = new Secs1OnTcpIpCommunicatorConfig();

        // 设置服务器地址和端口
        config.socketAddress(new InetSocketAddress(SERVER_IP, SERVER_PORT));

        // 设置设备ID
        config.deviceId(DEVICE_ID);

        // 设置为主控方(Master)
        config.isMaster(true);

        // 设置为设备端
        config.isEquip(IS_EQUIP);

        // 设置超时参数
        config.timeout().t1(1.0F);     // ENQ超时
        config.timeout().t2(15.0F);    // 回复超时
        config.timeout().t3(45.0F);    // 发送超时
        config.timeout().t4(45.0F);    // 中间响应超时

        // 设置重试次数
        config.retry(3);

        // 创建通信器
        this.communicator = Secs1OnTcpIpCommunicator.newInstance(config);

        // 添加消息接收监听器
        this.communicator.addSecsMessageReceiveListener(this::handleReceivedMessage);

        // 添加通信状态变化监听器
        this.communicator.addSecsCommunicatableStateChangeListener(this::handleCommunicationStateChange);
    }

    /**
     * 打开连接
     *
     * @throws IOException 如果打开连接失败
     */
    public void open() throws IOException {
        logger.info("正在打开SECS-I客户端连接...");
        this.communicator.open();
        logger.info("SECS-I客户端连接已打开");
    }

    /**
     * 关闭连接
     *
     * @throws IOException 如果关闭连接失败
     */
    public void close() throws IOException {
        logger.info("正在关闭SECS-I客户端连接...");
        this.communicator.close();
        logger.info("SECS-I客户端连接已关闭");
    }

    /**
     * 处理接收到的消息
     *
     * @param message 接收到的SECS消息
     */
    private void handleReceivedMessage(SecsMessage message) {
        try {
            int stream = message.getStream();
            int function = message.getFunction();
            boolean wbit = message.wbit();

            logger.info(String.format("收到消息: S%dF%d%s %s",
                    stream, function, (wbit ? " W" : ""), message.secs2()));

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
            logger.log(Level.WARNING, "处理接收消息时发生错误", e);
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
                        Secs2.ascii("MDLN-A"),    // 设备型号
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
            case 31: {
                // S2F31 W - Date and Time Set Request
                if (wbit) {
                    // 回复S2F32 - Date and Time Set Acknowledge
                    // 0x00 = 接受请求
                    this.communicator.send(message, 2, 32, false, Secs2.binary((byte)0x00));
                    logger.info("发送S2F32回复: 接受日期时间设置请求");
                }
                break;
            }
            case 33: {
                // S2F33 W - Define Report
                if (wbit) {
                    // 回复S2F34 - Define Report Acknowledge
                    // 0x00 = 接受请求
                    this.communicator.send(message, 2, 34, false, Secs2.binary((byte)0x00));
                    logger.info("发送S2F34回复: 接受报告定义请求");
                }
                break;
            }
            case 35: {
                // S2F35 W - Link Event Report
                if (wbit) {
                    // 回复S2F36 - Link Event Report Acknowledge
                    // 0x00 = 接受请求
                    this.communicator.send(message, 2, 36, false, Secs2.binary((byte)0x00));
                    logger.info("发送S2F36回复: 接受事件报告链接请求");
                }
                break;
            }
            case 37: {
                // S2F37 W - Enable/Disable Event Report
                if (wbit) {
                    // 回复S2F38 - Enable/Disable Event Report Acknowledge
                    // 0x00 = 接受请求
                    this.communicator.send(message, 2, 38, false, Secs2.binary((byte)0x00));
                    logger.info("发送S2F38回复: 接受事件报告启用/禁用请求");
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
                    // 回复S5F2 - Alarm Report Acknowledge
                    // 0x00 = 接受报警
                    this.communicator.send(message, 5, 2, false, Secs2.binary((byte)0x00));
                    logger.info("发送S5F2回复: 接受报警报告");
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
            case 11: {
                // S6F11 W - Event Report Send
                if (wbit) {
                    // 回复S6F12 - Event Report Acknowledge
                    // 0x00 = 接受事件报告
                    this.communicator.send(message, 6, 12, false, Secs2.binary((byte)0x00));
                    logger.info("发送S6F12回复: 接受事件报告");
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

        if (communicatable) {
            // 当通信建立时，可以执行一些初始化操作
            try {
                // 例如，发送S1F13建立通信
                sendS1F13EstablishCommunication();
            } catch (Exception e) {
                logger.log(Level.WARNING, "通信建立后初始化操作失败", e);
            }
        }
    }

    /**
     * 发送S1F1 Are You There请求
     *
     * @return 如果收到回复则返回true，否则返回false
     */
    public boolean sendS1F1AreYouThere() {
        try {
            logger.info("发送S1F1: Are You There");

            Optional<SecsMessage> reply = this.communicator.send(1, 1, true, Secs2.empty());

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                logger.info(String.format("收到S1F2回复: %s", msg.secs2()));
                return true;
            } else {
                logger.warning("未收到S1F2回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S1F1失败", e);
            return false;
        }
    }

    /**
     * 发送S1F13 Establish Communications请求
     *
     * @return 如果通信建立成功则返回true，否则返回false
     */
    public boolean sendS1F13EstablishCommunication() {
        try {
            logger.info("发送S1F13: Establish Communications");

            Optional<SecsMessage> reply = this.communicator.send(1, 13, true, Secs2.empty());

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] data = msg.secs2().getBytes();

                if (data.length > 0 && data[0] == 0x00) {
                    logger.info("通信建立成功");
                    return true;
                } else {
                    logger.warning("通信建立被拒绝");
                    return false;
                }
            } else {
                logger.warning("未收到S1F14回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S1F13失败", e);
            return false;
        }
    }

    /**
     * 发送S2F31 Date and Time Set请求
     *
     * @return 如果设置成功则返回true，否则返回false
     */
    public boolean sendS2F31DateTimeSet() {
        try {
            logger.info("发送S2F31: Date and Time Set");

            // 创建当前时间的SECS-II数据
            // 格式: A12 "YYYYMMDDHHMMSS"
            String now = String.format("%1$tY%1$tm%1$td%1$tH%1$tM%1$tS", System.currentTimeMillis());
            Secs2 data = Secs2.ascii(now);

            Optional<SecsMessage> reply = this.communicator.send(2, 31, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] replyData = msg.secs2().getBytes();

                if (replyData.length > 0 && replyData[0] == 0x00) {
                    logger.info("日期时间设置成功");
                    return true;
                } else {
                    logger.warning("日期时间设置被拒绝");
                    return false;
                }
            } else {
                logger.warning("未收到S2F32回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S2F31失败", e);
            return false;
        }
    }

    /**
     * 发送S5F1 Alarm Report通知
     *
     * @param alarmId 报警ID
     * @param alarmText 报警文本
     * @return 如果发送成功则返回true，否则返回false
     */
    public boolean sendS5F1AlarmReport(int alarmId, String alarmText) {
        try {
            logger.info(String.format("发送S5F1: Alarm Report (ID=%d, Text=%s)", alarmId, alarmText));

            // 创建报警数据
            // <L [2]
            //   <U4 alarmId>
            //   <A alarmText>
            // >
            Secs2 data = Secs2.list(
                Secs2.uint4(alarmId),
                Secs2.ascii(alarmText)
            );

            Optional<SecsMessage> reply = this.communicator.send(5, 1, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] replyData = msg.secs2().getBytes();

                if (replyData.length > 0 && replyData[0] == 0x00) {
                    logger.info("报警报告已确认");
                    return true;
                } else {
                    logger.warning("报警报告被拒绝");
                    return false;
                }
            } else {
                logger.warning("未收到S5F2回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S5F1失败", e);
            return false;
        }
    }

    /**
     * 发送S6F11 Event Report通知
     *
     * @param eventId 事件ID
     * @param reportId 报告ID
     * @param data 报告数据
     * @return 如果发送成功则返回true，否则返回false
     */
    public boolean sendS6F11EventReport(int eventId, int reportId, Secs2 data) {
        try {
            logger.info(String.format("发送S6F11: Event Report (EventID=%d, ReportID=%d)", eventId, reportId));

            // 创建事件报告数据
            // <L [3]
            //   <U4 eventId>
            //   <U4 reportId>
            //   <L [n] data>
            // >
            Secs2 reportData = Secs2.list(
                Secs2.uint4(eventId),
                Secs2.uint4(reportId),
                data
            );

            Optional<SecsMessage> reply = this.communicator.send(6, 11, true, reportData);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] replyData = msg.secs2().getBytes();

                if (replyData.length > 0 && replyData[0] == 0x00) {
                    logger.info("事件报告已确认");
                    return true;
                } else {
                    logger.warning("事件报告被拒绝");
                    return false;
                }
            } else {
                logger.warning("未收到S6F12回复");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送S6F11失败", e);
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

    /**
     * 主方法，用于测试
     */
    public static void main(String[] args) {
        try {
            // 创建SECS-I客户端
            Secs1TcpClientTest client = new Secs1TcpClientTest();

            try {
                // 打开连接
                client.open();

                // 等待连接建立
                Thread.sleep(2000);

                // 发送S1F1 Are You There请求
                boolean s1f1Result = client.sendS1F1AreYouThere();
                System.out.println("S1F1测试结果: " + (s1f1Result ? "成功" : "失败"));

                // 发送S1F13 Establish Communications请求
                boolean s1f13Result = client.sendS1F13EstablishCommunication();
                System.out.println("S1F13测试结果: " + (s1f13Result ? "成功" : "失败"));

                // 发送S2F31 Date and Time Set请求
                boolean s2f31Result = client.sendS2F31DateTimeSet();
                System.out.println("S2F31测试结果: " + (s2f31Result ? "成功" : "失败"));

                // 发送S5F1 Alarm Report通知
                boolean s5f1Result = client.sendS5F1AlarmReport(101, "测试报警");
                System.out.println("S5F1测试结果: " + (s5f1Result ? "成功" : "失败"));

                // 发送S6F11 Event Report通知
                Secs2 reportData = Secs2.list(
                    Secs2.list(
                        Secs2.ascii("PARAM1"),
                        Secs2.uint4(123)
                    ),
                    Secs2.list(
                        Secs2.ascii("PARAM2"),
                        Secs2.ascii("测试数据")
                    )
                );
                boolean s6f11Result = client.sendS6F11EventReport(201, 1, reportData);
                System.out.println("S6F11测试结果: " + (s6f11Result ? "成功" : "失败"));

                // 发送自定义消息
                Secs2 customData = Secs2.list(
                    Secs2.ascii("CUSTOM"),
                    Secs2.uint4(999)
                );
                Optional<SecsMessage> customReply = client.sendCustomMessage(9, 1, true, customData);
                System.out.println("自定义消息测试结果: " + (customReply.isPresent() ? "成功" : "失败"));

                // 保持连接一段时间，等待可能的服务器消息
                System.out.println("等待服务器消息...");
                Thread.sleep(30000);

            } finally {
                // 关闭连接
                client.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
