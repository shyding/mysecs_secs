package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.gem.ACKC5;
import com.shimizukenta.secs.gem.ACKC6;
import com.shimizukenta.secs.gem.COMMACK;
import com.shimizukenta.secs.secs1.Secs1GemAccessor;
import com.shimizukenta.secs.secs1.Secs1Message;
import com.shimizukenta.secs.secs1.Secs1MessageReceiveBiListener;
import com.shimizukenta.secs.secs1.impl.Secs1ValidMessage;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 多客户端SECS服务器测试类
 */
public class MultiClientSecsServerTest {

    private static final Logger logger = Logger.getLogger(MultiClientSecsServerTest.class.getName());

    /**
     * 主方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        try {
            // 创建服务器配置
            Secs1OnTcpIpReceiverCommunicatorConfig config = new Secs1OnTcpIpReceiverCommunicatorConfig();
            config.socketAddress(new InetSocketAddress("0.0.0.0", 5000));
            config.isEquip(false);  // 作为主机模式
            config.timeout().t3(45.0F);  // 设置T3超时为45秒
            config.timeout().t6(5.0F);   // 设置T6超时为5秒
            config.timeout().t8(5.0F);   // 设置T8超时为5秒

            // 创建并启动服务器
            MultiClientSecsServer server = new MultiClientSecsServer(config);

            // 初始化hostReceiveListener

            // 注册消息处理器
            System.out.println("注册消息处理器");
            server.addSecsMessageReceiveListener(msg -> {
                try {
                    System.out.println(String.format("收到消息，准备处理: S%dF%d%s",
                            msg.getStream(), msg.getFunction(), (msg.wbit() ? " W" : "")));
                    handleMessage(server, msg);
                } catch (Exception e) {
                    System.out.println("处理消息时出错: " + e.getMessage());
                    e.printStackTrace();
                    logger.log(Level.SEVERE, "处理消息时出错", e);
                }
            });

            // 注册Secs1MessageReceiveBiListener
            System.out.println("注册Secs1MessageReceiveBiListener");
            server.addSecs1MessageReceiveBiListener(hostReceiveListener);

            // 启动服务器
            System.out.println("启动服务器...");
            server.open();
            logger.info("服务器已启动，监听端口: " + config.socketAddress());
            System.out.println("服务器已启动，监听端口: " + config.socketAddress());

            // 启动命令行交互线程
            Thread commandThread = new Thread(() -> {
                try {
                    // 启动命令行交互
                    startCommandLineInterface(server);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "命令行交互出错", e);
                }
            });
            commandThread.setDaemon(true); // 设置为后台线程
            commandThread.start();

            // 等待用户输入以关闭服务器
            System.out.println("服务器已启动，输入'exit'停止服务器...");

            // 使用BufferedReader而不是System.in.read()，以支持命令行输入
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                if ("exit".equalsIgnoreCase(line.trim())) {
                    break;
                }
            }

            // 关闭服务器
            System.out.println("正在关闭服务器...");
            server.close();
            logger.info("服务器已关闭");

        } catch (IOException e) {
            logger.log(Level.SEVERE, "服务器运行出错", e);
        }
    }

    /**
     * 处理接收到的SECS消息
     *
     * @param server SECS服务器
     * @param message 接收到的消息
     * @throws SecsSendMessageException 如果发送消息失败
     * @throws SecsWaitReplyMessageException 如果等待回复超时
     * @throws SecsException 如果发生其他SECS异常
     * @throws InterruptedException 如果线程被中断
     */
    private static void handleMessage(MultiClientSecsServer server, SecsMessage message)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        int stream = message.getStream();
        int function = message.getFunction();
        boolean wbit = message.wbit();
        logger.info(String.format("收到消息: S%dF%d%s", stream, function, (wbit ? " W" : "")));
        Secs1ValidMessage msg =(Secs1ValidMessage)message;
        logger.info(String.format("收到消息: S%dF%d%s, 系统字节: %d",
                stream, function, (wbit ? " W" : ""), msg.getSourceAddress()));

        // 获取消息来源地址
        Optional<SocketAddress> sourceOpt = server.getMessageSource(message);
        if (!sourceOpt.isPresent()) {
            logger.warning("无法确定消息来源，无法回复");
            return;
        }

        SocketAddress source = sourceOpt.get();
        logger.info("消息来源: " + source);

        // 根据不同的消息类型进行处理
        if (stream == 1 && function == 1 && wbit) {
            // S1F1 W - Are You There
            handleS1F1(server, message, source);
        } else if (stream == 1 && function == 13 && wbit) {
            // S1F13 W - Establish Communications Request
            handleS1F13(server, message, source);
        } else if (stream == 2 && function == 31 && wbit) {
            // S2F31 W - Date and Time Request
            handleS2F31(server, message, source);
        } else if (wbit) {
            // 对于其他带W位的消息，发送通用拒绝响应
            sendRejectResponse(server, message, source);
        }
    }

    /**
     * 处理S1F1消息 (Are You There)
     */
    private static void handleS1F1(MultiClientSecsServer server, SecsMessage message, SocketAddress source)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        System.out.println("handleS1F1 - 开始处理S1F1消息，来源: " + source);

        try {
            // 创建S1F2响应 - 在线数据
            Secs2 replyData = Secs2.list(
                    Secs2.list(
                            Secs2.ascii("MDLN"),
                            Secs2.ascii("MULTICLIENT-SERVER")),
                    Secs2.list(
                            Secs2.ascii("SOFTREV"),
                            Secs2.ascii("1.0.0"))
            );

            System.out.println("handleS1F1 - 已创建S1F2响应数据: " + replyData);

            // 检查客户端连接
            ClientConnection connection = server.getConnectionManager().getConnection(source);
            if (connection == null) {
                System.out.println("handleS1F1 - 找不到客户端连接: " + source);
                throw new SecsSendMessageException("Client connection not found: " + source);
            }

            if (connection.isClosed()) {
                System.out.println("handleS1F1 - 客户端连接已关闭: " + source);
                throw new SecsSendMessageException("Client connection is closed: " + source);
            }

            System.out.println("handleS1F1 - 客户端连接状态: " +
                    (connection.isConnected() ? "已连接" : "未连接"));
            System.out.println("handleS1F1 - 通信器状态: " +
                    (connection.getCommunicator().isOpen() ? "已打开" : "未打开"));

            // 发送响应到特定客户端
            System.out.println("handleS1F1 - 准备发送S1F2响应到: " + source);
            server.send(source, 1, 2, false, replyData);
            System.out.println("handleS1F1 - 已发送S1F2响应到: " + source);
            logger.info("已发送S1F2响应到 " + source);

        } catch (Exception e) {
            System.out.println("handleS1F1 - 创建S1F2响应时出错: " + e.getMessage());
            e.printStackTrace();
            logger.log(Level.SEVERE, "创建S1F2响应时出错", e);
            throw e;
        }
    }

    /**
     * 处理S1F13消息 (Establish Communications Request)
     */
    private static void handleS1F13(MultiClientSecsServer server, SecsMessage message, SocketAddress source)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        try {
            // 创建S1F14响应 - 通信已建立，状态0 (成功)
            Secs2 replyData = Secs2.binary((byte)0x00);

            // 发送响应到特定客户端
            server.send(source, 1, 14, false, replyData);
            logger.info("已发送S1F14响应到 " + source);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "创建S1F14响应时出错", e);
        }
    }

    /**
     * 处理S2F31消息 (Date and Time Request)
     */
    private static void handleS2F31(MultiClientSecsServer server, SecsMessage message, SocketAddress source)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        try {
            // 获取当前时间
            java.time.LocalDateTime now = java.time.LocalDateTime.now();

            // 创建S2F32响应 - 日期和时间数据
            Secs2 replyData = Secs2.list(
                    Secs2.ascii(String.format("%04d", now.getYear())),
                    Secs2.ascii(String.format("%02d", now.getMonthValue())),
                    Secs2.ascii(String.format("%02d", now.getDayOfMonth())),
                    Secs2.ascii(String.format("%02d", now.getHour())),
                    Secs2.ascii(String.format("%02d", now.getMinute())),
                    Secs2.ascii(String.format("%02d", now.getSecond()))
            );

            // 发送响应到特定客户端
            server.send(source, 2, 32, false, replyData);
            logger.info("已发送S2F32响应到 " + source);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "创建S2F32响应时出错", e);
        }
    }

    /**
     * 发送通用拒绝响应
     */
    private static void sendRejectResponse(MultiClientSecsServer server, SecsMessage message, SocketAddress source)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        try {
            // 创建SxF0响应 - 功能不支持
            Secs2 replyData = Secs2.binary((byte)0x01); // 1 = 不支持的功能

            // 发送响应到特定客户端
            server.send(source, message.getStream(), 0, false, replyData);
            logger.info(String.format("已发送S%dF0响应到 %s", message.getStream(), source));

        } catch (Exception e) {
            logger.log(Level.SEVERE, "创建拒绝响应时出错", e);
        }
    }

    /**
     * 命令行交互测试方法
     *
     * @param args 命令行参数
     */
    public static void commandLineTest(String[] args) {
        try {
            // 创建服务器配置
            Secs1OnTcpIpReceiverCommunicatorConfig config = new Secs1OnTcpIpReceiverCommunicatorConfig();
            config.socketAddress(new InetSocketAddress("0.0.0.0", 5000));
            config.isEquip(false);  // 作为主机模式
            config.timeout().t3(45.0F);  // 设置T3超时为45秒
            config.timeout().t6(5.0F);   // 设置T6超时为5秒
            config.timeout().t8(5.0F);   // 设置T8超时为5秒

            // 创建并启动服务器
            MultiClientSecsServer server = new MultiClientSecsServer(config);
            server.open();
            logger.info("服务器已启动，监听端口: " + config.socketAddress());
            server.addSecs1MessageReceiveBiListener(hostReceiveListener);
            // 注册消息处理器
            server.addSecsMessageReceiveListener(msg -> {
                try {
                    handleMessage(server, msg);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "处理消息时出错", e);
                }
            });

            // 启动命令行交互
            startCommandLineInterface(server);

            // 关闭服务器
            server.close();
            logger.info("服务器已关闭");

        } catch (IOException e) {
            logger.log(Level.SEVERE, "服务器运行出错", e);
        }
    }

    /**
     * 启动命令行交互界面
     *
     * @param server SECS服务器
     */
    private static void startCommandLineInterface(MultiClientSecsServer server) {
        // 使用单独的Scanner实例，避免与main方法中的BufferedReader冲突
        Scanner scanner = new Scanner(new BufferedReader(new InputStreamReader(System.in)));
        boolean running = true;

        System.out.println("\n=== 多客户端SECS服务器命令行界面 ===");
        printHelp();

        while (running) {
            try {
                System.out.print("\n请输入命令> ");

                // 检查Scanner是否还有输入
                if (!scanner.hasNextLine()) {
                    System.out.println("输入流已关闭，命令行界面将退出");
                    break;
                }

                String command = scanner.nextLine().trim();

                // 如果输入为空，继续循环
                if (command.isEmpty()) {
                    continue;
                }

                try {
                    switch (command) {
                        case "help":
                            printHelp();
                            break;

                        case "list":
                            listClients(server);
                            break;

                        case "count":
                            countClients(server);
                            break;

                        case "info":
                            System.out.print("请输入客户端索引: ");
                            if (!scanner.hasNextLine()) {
                                System.out.println("输入流已关闭");
                                break;
                            }
                            String indexStr = scanner.nextLine().trim();
                            if (indexStr.isEmpty()) {
                                System.out.println("索引不能为空");
                                break;
                            }
                            try {
                                int clientIndex = Integer.parseInt(indexStr);
                                showClientInfo(server, clientIndex);
                            } catch (NumberFormatException e) {
                                System.out.println("无效的索引格式: " + indexStr);
                            }
                            break;

                        case "send":
                            handleSendCommand(server, scanner);
                            break;

                        case "s1f1":
                            handleS1F1Command(server, scanner);
                            break;

                        case "s1f13":
                            handleS1F13Command(server, scanner);
                            break;

                        case "s2f31":
                            handleS2F31Command(server, scanner);
                            break;

                        case "exit":
                            running = false;
                            System.out.println("命令行界面已退出，服务器仍在运行");
                            System.out.println("在主程序中输入'exit'以停止服务器");
                            break;

                        default:
                            System.out.println("未知命令: " + command);
                            printHelp();
                            break;
                    }
                } catch (Exception e) {
                    System.out.println("命令执行出错: " + e.getMessage());
                    e.printStackTrace();
                }
            } catch (Exception e) {
                System.out.println("命令行输入处理出错: " + e.getMessage());
                e.printStackTrace();
                // 等待一会儿再继续，避免循环过快
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        try {
            scanner.close();
        } catch (Exception e) {
            // 忽略关闭异常
        }
    }

    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("\n可用命令:");
        System.out.println("  help   - 显示帮助信息");
        System.out.println("  list   - 列出所有连接的客户端");
        System.out.println("  count  - 显示客户端数量");
        System.out.println("  info   - 显示指定客户端的详细信息");
        System.out.println("  send   - 向指定客户端发送自定义SECS消息");
        System.out.println("  s1f1   - 向指定客户端发送S1F1消息");
        System.out.println("  s1f13  - 向指定客户端发送S1F13消息");
        System.out.println("  s2f31  - 向指定客户端发送S2F31消息");
        System.out.println("  exit   - 退出程序");
    }

    private static final Secs1MessageReceiveBiListener hostReceiveListener = (Secs1Message primaryMsg, Secs1GemAccessor comm) -> {

        int strm = primaryMsg.getStream();
        int func = primaryMsg.getFunction();
        boolean wbit = primaryMsg.wbit();
        logger.info(String.format("收到主机消息: S%dF%d%s", strm, func, (wbit ? " W" : "")));
        System.out.println("MultiClientSecsServerTest.hostReceiveListener: ");
        try {

            switch (strm) {
                case 1: {

                    switch (func) {
                        case 1: {
                            if (wbit) {
                                comm.gem().s1f2(primaryMsg);
                            }
                            break;
                        }
                        case 13: {
                            if (wbit) {
                                comm.gem().s1f14(primaryMsg, COMMACK.OK);
                            }
                        }
                        default: {
                            if (wbit) {
                                comm.send(primaryMsg, strm, 0, false);
                            }
                        }
                    }
                    break;
                }
                case 2: {

                    switch (func) {
                        case 17: {
                            if (wbit) {
                                comm.gem().s2f18Now(primaryMsg);
                            }
                        }
                        default: {
                            if (wbit) {
                                comm.send(primaryMsg, strm, 0, false);
                            }
                        }
                    }
                    break;
                }
                case 5: {

                    switch (func) {
                        case 11: {
                            if (wbit) {
                                comm.gem().s5f2(primaryMsg, ACKC5.OK);
                            }
                            break;
                        }
                        default: {
                            if (wbit) {
                                comm.send(primaryMsg, strm, 0, false);
                            }
                        }
                    }
                    break;
                }
                case 6: {

                    switch (func) {
                        case 11: {
                            if (wbit) {
                                comm.gem().s6f12(primaryMsg, ACKC6.OK);
                            }
                            break;
                        }
                        default: {
                            if (wbit) {
                                comm.send(primaryMsg, strm, 0, false);
                            }
                        }
                    }
                    break;
                }
                default: {
                    if (wbit) {
                        comm.send(primaryMsg, 0, 0, false);
                    }
                }
            }
        }
        catch (SecsException e) {

        }
        catch (InterruptedException ignore) {
        }
    };

    /**
     * 列出所有客户端
     */
    private static void listClients(MultiClientSecsServer server) {
        Collection<ClientConnection> clients = server.getConnections();

        if (clients.isEmpty()) {
            System.out.println("当前没有客户端连接");
            return;
        }

        System.out.println("\n已连接的客户端:");
        int index = 0;
        for (ClientConnection client : clients) {
            System.out.println(String.format("  [%d] %s", index++, client.getRemoteSocketAddress()));
        }
    }

    /**
     * 显示客户端数量
     */
    private static void countClients(MultiClientSecsServer server) {
        int count = server.getConnections().size();
        System.out.println(String.format("当前客户端数量: %d", count));
    }

    /**
     * 显示客户端详细信息
     */
    private static void showClientInfo(MultiClientSecsServer server, int index) {
        List<ClientConnection> clients = new ArrayList<>(server.getConnections());

        if (index < 0 || index >= clients.size()) {
            System.out.println("无效的客户端索引");
            return;
        }

        ClientConnection client = clients.get(index);
        System.out.println("\n客户端信息:");
        System.out.println("  远程地址: " + client.getRemoteSocketAddress());
        System.out.println("  连接状态: " + (client.isConnected() ? "已连接" : "已断开"));
        System.out.println("  最后活动时间: " + new java.util.Date(client.getLastActivityTime()));
    }

    /**
     * 处理发送自定义消息命令
     */
    private static void handleSendCommand(MultiClientSecsServer server, Scanner scanner)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        List<ClientConnection> clients = new ArrayList<>(server.getConnections());
        if (clients.isEmpty()) {
            System.out.println("当前没有客户端连接");
            return;
        }

        // 获取客户端索引
        System.out.print("请输入客户端索引: ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String indexStr = scanner.nextLine().trim();
        if (indexStr.isEmpty()) {
            System.out.println("索引不能为空");
            return;
        }

        int clientIndex;
        try {
            clientIndex = Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            System.out.println("无效的索引格式: " + indexStr);
            return;
        }

        if (clientIndex < 0 || clientIndex >= clients.size()) {
            System.out.println("无效的客户端索引");
            return;
        }

        // 获取Stream编号
        System.out.print("请输入Stream编号: ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String streamStr = scanner.nextLine().trim();
        if (streamStr.isEmpty()) {
            System.out.println("Stream编号不能为空");
            return;
        }

        int stream;
        try {
            stream = Integer.parseInt(streamStr);
            if (stream < 0 || stream > 127) {
                System.out.println("Stream编号必须在0-127范围内");
                return;
            }
        } catch (NumberFormatException e) {
            System.out.println("无效的Stream编号格式: " + streamStr);
            return;
        }

        // 获取Function编号
        System.out.print("请输入Function编号: ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String functionStr = scanner.nextLine().trim();
        if (functionStr.isEmpty()) {
            System.out.println("Function编号不能为空");
            return;
        }

        int function;
        try {
            function = Integer.parseInt(functionStr);
            if (function < 0 || function > 255) {
                System.out.println("Function编号必须在0-255范围内");
                return;
            }
        } catch (NumberFormatException e) {
            System.out.println("无效的Function编号格式: " + functionStr);
            return;
        }

        // 获取W-Bit设置
        System.out.print("是否设置W-Bit (y/n): ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String wbitStr = scanner.nextLine().trim();
        boolean wbit = wbitStr.equalsIgnoreCase("y") || wbitStr.equalsIgnoreCase("yes");

        // 获取消息内容
        System.out.print("请输入消息内容 (简单文本): ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String messageText = scanner.nextLine().trim();

        // 检查客户端连接状态
        ClientConnection client = clients.get(clientIndex);
        if (!client.isConnected()) {
            System.out.println("客户端已断开连接");
            return;
        }

        SocketAddress clientAddress = client.getRemoteSocketAddress();

        try {
            // 创建消息
            Secs2 messageData = Secs2.ascii(messageText);

            // 发送消息
            System.out.println(String.format("正在发送S%dF%d%s消息到客户端: %s",
                    stream, function, (wbit ? " W" : ""), clientAddress));

            if (wbit) {
                SecsMessage reply = server.sendAndWaitReply(clientAddress, stream, function, messageData);
                System.out.println("收到回复: " + reply.secs2());
            } else {
                server.send(clientAddress, stream, function, wbit, messageData);
                System.out.println("消息已发送");
            }
        } catch (SecsSendMessageException e) {
            System.out.println("发送消息失败: " + e.getMessage());
        } catch (SecsWaitReplyMessageException e) {
            System.out.println("等待回复超时: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("创建消息数据失败: " + e.getMessage());
        }
    }

    /**
     * 处理发送S1F1命令
     */
    private static void handleS1F1Command(MultiClientSecsServer server, Scanner scanner)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        List<ClientConnection> clients = new ArrayList<>(server.getConnections());
        if (clients.isEmpty()) {
            System.out.println("当前没有客户端连接");
            return;
        }

        System.out.print("请输入客户端索引: ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String indexStr = scanner.nextLine().trim();
        if (indexStr.isEmpty()) {
            System.out.println("索引不能为空");
            return;
        }

        int clientIndex;
        try {
            clientIndex = Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            System.out.println("无效的索引格式: " + indexStr);
            return;
        }

        if (clientIndex < 0 || clientIndex >= clients.size()) {
            System.out.println("无效的客户端索引");
            return;
        }

        // 发送S1F1消息
        ClientConnection client = clients.get(clientIndex);
        if (!client.isConnected()) {
            System.out.println("客户端已断开连接");
            return;
        }

        SocketAddress clientAddress = client.getRemoteSocketAddress();
        System.out.println("正在发送S1F1消息到客户端: " + clientAddress);

        try {
            SecsMessage reply = server.sendAndWaitReply(clientAddress, 1, 1, Secs2.empty());
            System.out.println("收到S1F2回复: " + reply.secs2());
        } catch (SecsSendMessageException e) {
            System.out.println("发送S1F1消息失败: " + e.getMessage());
        } catch (SecsWaitReplyMessageException e) {
            System.out.println("等待S1F2回复超时: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("发送S1F1消息时发生错误: " + e.getMessage());
        }
    }

    /**
     * 处理发送S1F13命令
     */
    private static void handleS1F13Command(MultiClientSecsServer server, Scanner scanner)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        List<ClientConnection> clients = new ArrayList<>(server.getConnections());
        if (clients.isEmpty()) {
            System.out.println("当前没有客户端连接");
            return;
        }

        System.out.print("请输入客户端索引: ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String indexStr = scanner.nextLine().trim();
        if (indexStr.isEmpty()) {
            System.out.println("索引不能为空");
            return;
        }

        int clientIndex;
        try {
            clientIndex = Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            System.out.println("无效的索引格式: " + indexStr);
            return;
        }

        if (clientIndex < 0 || clientIndex >= clients.size()) {
            System.out.println("无效的客户端索引");
            return;
        }

        // 发送S1F13消息
        ClientConnection client = clients.get(clientIndex);
        if (!client.isConnected()) {
            System.out.println("客户端已断开连接");
            return;
        }

        SocketAddress clientAddress = client.getRemoteSocketAddress();
        System.out.println("正在发送S1F13消息到客户端: " + clientAddress);

        try {
            SecsMessage reply = server.sendAndWaitReply(clientAddress, 1, 13, Secs2.empty());
            System.out.println("收到S1F14回复: " + reply.secs2());
        } catch (SecsSendMessageException e) {
            System.out.println("发送S1F13消息失败: " + e.getMessage());
        } catch (SecsWaitReplyMessageException e) {
            System.out.println("等待S1F14回复超时: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("发送S1F13消息时发生错误: " + e.getMessage());
        }
    }

    /**
     * 处理发送S2F31命令
     */
    private static void handleS2F31Command(MultiClientSecsServer server, Scanner scanner)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {

        List<ClientConnection> clients = new ArrayList<>(server.getConnections());
        if (clients.isEmpty()) {
            System.out.println("当前没有客户端连接");
            return;
        }

        System.out.print("请输入客户端索引: ");
        if (!scanner.hasNextLine()) {
            System.out.println("输入流已关闭");
            return;
        }

        String indexStr = scanner.nextLine().trim();
        if (indexStr.isEmpty()) {
            System.out.println("索引不能为空");
            return;
        }

        int clientIndex;
        try {
            clientIndex = Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            System.out.println("无效的索引格式: " + indexStr);
            return;
        }

        if (clientIndex < 0 || clientIndex >= clients.size()) {
            System.out.println("无效的客户端索引");
            return;
        }

        // 发送S2F31消息
        ClientConnection client = clients.get(clientIndex);
        if (!client.isConnected()) {
            System.out.println("客户端已断开连接");
            return;
        }

        SocketAddress clientAddress = client.getRemoteSocketAddress();
        System.out.println("正在发送S2F31消息到客户端: " + clientAddress);

        try {
            SecsMessage reply = server.sendAndWaitReply(clientAddress, 2, 31, Secs2.empty());
            System.out.println("收到S2F32回复: " + reply.secs2());
        } catch (SecsSendMessageException e) {
            System.out.println("发送S2F31消息失败: " + e.getMessage());
        } catch (SecsWaitReplyMessageException e) {
            System.out.println("等待S2F32回复超时: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("发送S2F31消息时发生错误: " + e.getMessage());
        }
    }

    /**
     * 多线程测试方法
     *
     * @param args 命令行参数
     */
    public static void multiThreadTest(String[] args) {
        try {
            // 创建服务器配置
            Secs1OnTcpIpReceiverCommunicatorConfig config = new Secs1OnTcpIpReceiverCommunicatorConfig();
            config.socketAddress(new InetSocketAddress("0.0.0.0", 5000));
            config.isEquip(false);

            // 创建并启动服务器
            MultiClientSecsServer server = new MultiClientSecsServer(config);
            server.open();
            logger.info("服务器已启动，监听端口: " + config.socketAddress());

            // 创建线程池
            ExecutorService executor = Executors.newFixedThreadPool(10);

            // 注册消息处理器
            server.addSecsMessageReceiveListener(msg -> {
                // 在线程池中处理消息
                executor.submit(() -> {
                    try {
                        handleMessage(server, msg);
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "处理消息时出错", e);
                    }
                });
            });

            // 等待用户输入以关闭服务器
            System.out.println("按Enter键停止服务器...");
            System.in.read();

            // 关闭线程池和服务器
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            server.close();
            logger.info("服务器已关闭");

        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "服务器运行出错", e);
        }
    }
}
