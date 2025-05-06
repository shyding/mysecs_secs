/*
package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Scanner;
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

*/
/**
 * SECS-I TCP客户端交互式测试工具
 *
 * 这个类提供了一个命令行界面，允许用户通过交互方式发送各种SECS消息到服务器，
 * 并查看响应结果。支持常见的SECS消息类型，如S1F1, S1F13, S2F31等。
 *
 * 主要功能：
 * 1. 交互式命令行界面
 * 2. 支持发送各种SECS消息
 * 3. 显示详细的通信日志
 * 4. 可配置的连接参数
 *//*

public class Secs1MultiClientTester {

    private static final Logger logger = Logger.getLogger(Secs1MultiClientTester.class.getName());

    // 默认配置参数
    private String serverIp = "127.0.0.1";
    private int serverPort = 5000;
    private int deviceId = 10;
    private boolean isEquip = true;
    private boolean isMaster = false;

    // 通信器实例
    private SecsCommunicator communicator;
    private boolean running = true;

    */
/**
     * 构造函数
     *//*

    public Secs1MultiClientTester() {
        // 默认构造函数
    }

    */
/**
     * 初始化通信器
     *
     * @throws IOException 如果创建通信器失败
     *//*

    public void initialize() throws IOException {
        // 创建SECS-I配置
        Secs1OnTcpIpCommunicatorConfig config = new Secs1OnTcpIpCommunicatorConfig();

        // 设置服务器地址和端口
        config.socketAddress(new InetSocketAddress(serverIp, serverPort));

        // 设置设备ID
        config.deviceId(deviceId);

        // 设置为主控方(Master)或从属方(Slave)
        config.isMaster(isMaster);

        // 设置为设备端或主机端
        config.isEquip(isEquip);

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

    */
/**
     * 打开连接
     *
     * @throws IOException 如果打开连接失败
     *//*

    public void open() throws IOException {
        logger.info("正在打开SECS-I客户端连接...");
        this.communicator.open();
        logger.info("SECS-I客户端连接已打开");
    }

    */
/**
     * 关闭连接
     *
     * @throws IOException 如果关闭连接失败
     *//*

    public void close() throws IOException {
        logger.info("正在关闭SECS-I客户端连接...");
        this.communicator.close();
        logger.info("SECS-I客户端连接已关闭");
    }

    */
/**
     * 处理接收到的消息
     *
     * @param message 接收到的SECS消息
     *//*

    private void handleReceivedMessage(SecsMessage message) {
        try {
            int stream = message.getStream();
            int function = message.getFunction();
            boolean wbit = message.wbit();

            System.out.println("\n收到消息: S" + stream + "F" + function + (wbit ? " W" : "") + " " + message.secs2());

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

    */
/**
     * 处理Stream 1消息
     *//*

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
                    System.out.println("发送S1F2回复: " + reply);
                }
                break;
            }
            case 13: {
                // S1F13 W - Establish Communications Request
                if (wbit) {
                    // 回复S1F14 - Establish Communications Request Acknowledge
                    // 0x00 = 通信已建立
                    this.communicator.send(message, 1, 14, false, Secs2.binary((byte)0x00));
                    System.out.println("发送S1F14回复: 通信已建立");
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

    */
/**
     * 处理Stream 2消息
     *//*

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
                    System.out.println("发送S2F32回复: 接受日期时间设置请求");
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

    */
/**
     * 处理Stream 5消息
     *//*

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
                    System.out.println("发送S5F2回复: 接受报警报告");
                }
                break;
            }
            case 5: {
                // S5F5 W - LinkTest.Request
                if (wbit) {
                    // 回复S5F6 - LinkTest.Acknowledge
                    this.communicator.send(message, 5, 6, false, Secs2.empty());
                    System.out.println("发送S5F6回复: 心跳响应");
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

    */
/**
     * 处理Stream 6消息
     *//*

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
                    System.out.println("发送S6F12回复: 接受事件报告");
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

    */
/**
     * 发送通用的SxF0响应（未知函数响应）
     *//*

    private void sendSxF0Response(SecsMessage message) throws InterruptedException, SecsException {
        int stream = message.getStream();

        // 0x01 = 未知函数
        this.communicator.send(message, stream, 0, false, Secs2.binary((byte)0x01));
        System.out.println(String.format("发送S%dF0回复: 未知函数", stream));
    }

    */
/**
     * 处理通信状态变化
     *//*

    private void handleCommunicationStateChange(boolean communicatable) {
        System.out.println("\n通信状态变更为: " + (communicatable ? "可通信" : "不可通信"));
    }

    */
/**
     * 发送S1F1 Are You There请求
     *//*

    public void sendS1F1AreYouThere() {
        try {
            System.out.println("发送S1F1: Are You There");

            Optional<SecsMessage> reply = this.communicator.send(1, 1, true, Secs2.empty());

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                System.out.println("收到S1F2回复: " + msg.secs2());
            } else {
                System.out.println("未收到S1F2回复");
            }
        } catch (Exception e) {
            System.out.println("发送S1F1失败: " + e.getMessage());
        }
    }

    */
/**
     * 发送S1F13 Establish Communications请求
     *//*

    public void sendS1F13EstablishCommunication() {
        try {
            System.out.println("发送S1F13: Establish Communications");

            Optional<SecsMessage> reply = this.communicator.send(1, 13, true, Secs2.empty());

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] data = msg.secs2().getBytes();

                if (data.length > 0 && data[0] == 0x00) {
                    System.out.println("通信建立成功");
                } else {
                    System.out.println("通信建立被拒绝");
                }
            } else {
                System.out.println("未收到S1F14回复");
            }
        } catch (Exception e) {
            System.out.println("发送S1F13失败: " + e.getMessage());
        }
    }

    */
/**
     * 发送S2F31 Date and Time Set请求
     *//*

    public void sendS2F31DateTimeSet() {
        try {
            System.out.println("发送S2F31: Date and Time Set");

            // 创建当前时间的SECS-II数据
            // 格式: A12 "YYYYMMDDHHMMSS"
            String now = String.format("%1$tY%1$tm%1$td%1$tH%1$tM%1$tS", System.currentTimeMillis());
            Secs2 data = Secs2.ascii(now);

            Optional<SecsMessage> reply = this.communicator.send(2, 31, true, data);

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                byte[] replyData = msg.secs2().getBytes();

                if (replyData.length > 0 && replyData[0] == 0x00) {
                    System.out.println("日期时间设置成功");
                } else {
                    System.out.println("日期时间设置被拒绝");
                }
            } else {
                System.out.println("未收到S2F32回复");
            }
        } catch (Exception e) {
            System.out.println("发送S2F31失败: " + e.getMessage());
        }
    }

    */
/**
     * 发送S5F5 LinkTest.Request请求
     *//*

    public void sendS5F5LinkTest() {
        try {
            System.out.println("发送S5F5: LinkTest.Request (心跳)");

            Optional<SecsMessage> reply = this.communicator.send(5, 5, true, Secs2.empty());

            if (reply.isPresent()) {
                SecsMessage msg = reply.get();
                System.out.println("收到S5F6回复: 心跳响应成功");
            } else {
                System.out.println("未收到S5F6回复");
            }
        } catch (Exception e) {
            System.out.println("发送S5F5失败: " + e.getMessage());
        }
    }

    */
/**
     * 发送自定义SECS消息
     *//*

    public void sendCustomMessage() {
        try {
            Scanner scanner = new Scanner(System.in);
            
            System.out.print("请输入Stream编号: ");
            int stream = Integer.parseInt(scanner.nextLine().trim());
            
            System.out.print("请输入Function编号: ");
            int function = Integer.parseInt(scanner.nextLine().trim());
            
            System.out.print("是否需要回复(y/n): ");
            boolean wbit = scanner.nextLine().trim().equalsIgnoreCase("y");
            
            System.out.print("请输入消息类型(1=空, 2=ASCII文本, 3=数字, 4=二进制): ");
            int dataType = Integer.parseInt(scanner.nextLine().trim());
            
            Secs2 data;
            
            switch (dataType) {
                case 1:
                    data = Secs2.empty();
                    break;
                case 2:
                    System.out.print("请输入ASCII文本: ");
                    String text = scanner.nextLine();
                    data = Secs2.ascii(text);
                    break;
                case 3:
                    System.out.print("请输入数字: ");
                    int number = Integer.parseInt(scanner.nextLine().trim());
                    data = Secs2.uint4(number);
                    break;
                case 4:
                    System.out.print("请输入十六进制字节(如: 01 02 03): ");
                    String hexStr = scanner.nextLine().trim();
                    String[] hexBytes = hexStr.split("\\s+");
                    byte[] bytes = new byte[hexBytes.length];
                    for (int i = 0; i < hexBytes.length; i++) {
                        bytes[i] = (byte) Integer.parseInt(hexBytes[i], 16);
                    }
                    data = Secs2.binary(bytes);
                    break;
                default:
                    data = Secs2.empty();
            }
            
            System.out.println(String.format("发送自定义消息: S%dF%d%s %s",
                    stream, function, (wbit ? " W" : ""), data));
            
            Optional<SecsMessage> reply = this.communicator.send(stream, function, wbit, data);
            
            if (wbit) {
                if (reply.isPresent()) {
                    SecsMessage msg = reply.get();
                    System.out.println("收到回复: S" + msg.getStream() + "F" + msg.getFunction() + " " + msg.secs2());
                } else {
                    System.out.println("未收到回复");
                }
            } else {
                System.out.println("消息已发送");
            }
        } catch (Exception e) {
            System.out.println("发送自定义消息失败: " + e.getMessage());
        }
    }

    */
/**
     * 显示帮助信息
     *//*

    private void printHelp() {
        System.out.println("\n=== SECS-I TCP客户端交互式测试工具 ===");
        System.out.println("可用命令:");
        System.out.println("  help    - 显示帮助信息");
        System.out.println("  config  - 配置连接参数");
        System.out.println("  connect - 连接到服务器");
        System.out.println("  s1f1    - 发送S1F1 Are You There请求");
        System.out.println("  s1f13   - 发送S1F13 Establish Communications请求");
        System.out.println("  s2f31   - 发送S2F31 Date and Time Set请求");
        System.out.println("  s5f5    - 发送S5F5 LinkTest.Request请求");
        System.out.println("  custom  - 发送自定义SECS消息");
        System.out.println("  status  - 显示连接状态");
        System.out.println("  close   - 关闭连接");
        System.out.println("  exit    - 退出程序");
    }

    */
/**
     * 配置连接参数
     *//*

    private void configureConnection() {
        try {
            Scanner scanner = new Scanner(System.in);
            
            System.out.println("\n=== 配置连接参数 ===");
            System.out.println("当前配置:");
            System.out.println("  服务器IP: " + serverIp);
            System.out.println("  服务器端口: " + serverPort);
            System.out.println("  设备ID: " + deviceId);
            System.out.println("  设备类型: " + (isEquip ? "设备端(Equipment)" : "主机端(Host)"));
            System.out.println("  通信模式: " + (isMaster ? "主控方(Master)" : "从属方(Slave)"));
            
            System.out.print("\n是否修改配置(y/n): ");
            if (!scanner.nextLine().trim().equalsIgnoreCase("y")) {
                return;
            }
            
            System.out.print("服务器IP [" + serverIp + "]: ");
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                serverIp = input;
            }
            
            System.out.print("服务器端口 [" + serverPort + "]: ");
            input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                serverPort = Integer.parseInt(input);
            }
            
            System.out.print("设备ID [" + deviceId + "]: ");
            input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                deviceId = Integer.parseInt(input);
            }
            
            System.out.print("设备类型 (1=设备端/Equipment, 0=主机端/Host) [" + (isEquip ? "1" : "0") + "]: ");
            input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                isEquip = input.equals("1");
            }
            
            System.out.print("通信模式 (1=主控方/Master, 0=从属方/Slave) [" + (isMaster ? "1" : "0") + "]: ");
            input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                isMaster = input.equals("1");
            }
            
            System.out.println("\n新配置已保存:");
            System.out.println("  服务器IP: " + serverIp);
            System.out.println("  服务器端口: " + serverPort);
            System.out.println("  设备ID: " + deviceId);
            System.out.println("  设备类型: " + (isEquip ? "设备端(Equipment)" : "主机端(Host)"));
            System.out.println("  通信模式: " + (isMaster ? "主控方(Master)" : "从属方(Slave)"));
            
            // 如果已经连接，提示需要重新连接
            if (communicator != null && communicator.isCommunicatable()) {
                System.out.println("\n注意: 配置已更改，需要重新连接才能生效");
            }
        } catch (Exception e) {
            System.out.println("配置过程中发生错误: " + e.getMessage());
        }
    }

    */
/**
     * 显示连接状态
     *//*

    private void showStatus() {
        System.out.println("\n=== 连接状态 ===");
        if (communicator == null) {
            System.out.println("通信器未初始化");
            return;
        }
        
        System.out.println("通信器已初始化: " + (communicator != null));
        System.out.println("连接状态: " + (communicator.isCommunicatable() ? "已连接" : "未连接"));
        System.out.println("配置信息:");
        System.out.println("  服务器地址: " + serverIp + ":" + serverPort);
        System.out.println("  设备ID: " + deviceId);
        System.out.println("  设备类型: " + (isEquip ? "设备端(Equipment)" : "主机端(Host)"));
        System.out.println("  通信模式: " + (isMaster ? "主控方(Master)" : "从属方(Slave)"));
    }

    */
/**
     * 启动命令行界面
     *//*

    public void startCommandLine() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        printHelp();
        
        while (running) {
            try {
                System.out.print("\n请输入命令> ");
                String command = reader.readLine().trim().toLowerCase();
                
                switch (command) {
                    case "help":
                        printHelp();
                        break;
                    case "config":
                        configureConnection();
                        break;
                    case "connect":
                        if (communicator != null && communicator.isCommunicatable()) {
                            System.out.println("已经连接到服务器");
                        } else {
                            if (communicator != null) {
                                try {
                                    communicator.close();
                                } catch (Exception ignore) {
                                }
                            }
                            initialize();
                            open();
                        }
                        break;
                    case "s1f1":
                        if (checkConnection()) {
                            sendS1F1AreYouThere();
                        }
                        break;
                    case "s1f13":
                        if (checkConnection()) {
                            sendS1F13EstablishCommunication();
                        }
                        break;
                    case "s2f31":
                        if (checkConnection()) {
                            sendS2F31DateTimeSet();
                        }
                        break;
                    case "s5f5":
                        if (checkConnection()) {
                            sendS5F5LinkTest();
                        }
                        break;
                    case "custom":
                        if (checkConnection()) {
                            sendCustomMessage();
                        }
                        break;
                    case "status":
                        showStatus();
                        break;
                    case "close":
                        if (communicator != null) {
                            close();
                        } else {
                            System.out.println("未连接到服务器");
                        }
                        break;
                    case "exit":
                        running = false;
                        if (communicator != null) {
                            try {
                                close();
                            } catch (Exception ignore) {
                            }
                        }
                        break;
                    default:
                        System.out.println("未知命令: " + command);
                        System.out.println("输入'help'查看可用命令");
                }
            } catch (Exception e) {
                System.out.println("执行命令时发生错误: " + e.getMessage());
            }
        }
    }

    */
/**
     * 检查连接状态
     *//*

    private boolean checkConnection() {
        if (communicator == null || !communicator.isCommunicatable()) {
            System.out.println("未连接到服务器，请先使用'connect'命令连接");
            return false;
        }
        return true;
    }

    */
/**
     * 主方法
     *//*

    public static void main(String[] args) {
        System.out.println("启动SECS-I TCP客户端交互式测试工具...");
        
        Secs1MultiClientTester tester = new Secs1MultiClientTester();
        tester.startCommandLine();
        
        System.out.println("SECS-I TCP客户端交互式测试工具已退出");
    }
}
*/
