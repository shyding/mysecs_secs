package example8;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs1ontcpip.ext.multiclient.ClientConnection;
import com.shimizukenta.secs.secs1ontcpip.ext.multiclient.MultiClientSecsServer;
import com.shimizukenta.secs.secs2.Secs2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 多客户端SECS服务器测试类
 */
public class MultiClientSecsServerTest {

    private static final Logger logger = Logger.getLogger(MultiClientSecsServerTest.class.getName());

    // 配置参数
    private static final String SERVER_IP = "127.0.0.1";  // 服务器IP地址
    private static final int SERVER_PORT = 5000;          // 服务器端口
    private static final int DEVICE_ID = 0;              // 设备ID
    private static final boolean IS_EQUIP = false;        // 是否为设备端
    private static final long HEARTBEAT_INTERVAL = 30000; // 心跳间隔（毫秒）
    private static final long CONNECTION_TIMEOUT = 60000; // 连接超时（毫秒）

    /**
     * 主方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        try {
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

            // 设置超时参数
            config.timeout().t1(1.0F);     // ENQ超时 - 等待数据块到达的时间
            config.timeout().t2(5.0F);     // 回复超时 - 等待EOT/ACK/NAK的时间
            config.timeout().t3(45.0F);    // 发送超时 - 等待消息回复的时间
            config.timeout().t4(45.0F);    // 中间响应超时 - 等待下一个数据块的时间

            // 设置重试次数
            config.retry(5);  // 增加重试次数以提高可靠性

            // 设置日志级别
            java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
            rootLogger.setLevel(Level.INFO);
            for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
                handler.setLevel(Level.INFO);
            }

            // 设置重试次数
            config.retry(3);

            // 创建多客户端SECS服务器
            try (MultiClientSecsServer server = new MultiClientSecsServer(
                    config, SERVER_IP, SERVER_PORT, HEARTBEAT_INTERVAL, CONNECTION_TIMEOUT)) {

                // 设置客户端连接处理器
                server.setClientConnectedHandler(connection -> {
                    logger.info("客户端已连接: " + connection.getRemoteAddress());
                    System.out.println("客户端已连接: " + connection.getRemoteAddress());

                    // 注意：根据SECS-I协议，服务器不应该在客户端连接后立即发送消息
                    // 而应该等待客户端发起通信（如发送ENQ消息）
                    logger.info("等待客户端发起通信: " + connection.getRemoteAddress());
                    System.out.println("等待客户端发起通信: " + connection.getRemoteAddress());
                });

                // 设置客户端断开连接处理器
                server.setClientDisconnectedHandler(connection -> {
                    logger.info("客户端已断开连接: " + connection.getRemoteAddress());
                });

                // 设置消息接收处理器
                server.setMessageReceivedHandler((message, sourceAddress) -> {
                    // 详细记录收到的消息
                    String msgInfo = String.format("收到来自 %s 的消息: S%dF%d%s",
                            sourceAddress, message.getStream(), message.getFunction(),
                            (message.wbit() ? " W" : ""));

                    // 打印消息内容
                    logger.info(msgInfo);
                    System.out.println(msgInfo);

                    try {
                        // 打印消息数据
                        String dataInfo = "消息数据: " + message.secs2().toString();
                        logger.info(dataInfo);
                        System.out.println(dataInfo);
                    } catch (Exception e) {
                        logger.warning("解析消息数据失败: " + e.getMessage());
                    }

                    // 处理自定义消息
                    handleCustomMessage(server, message, sourceAddress);
                });

                // 启动服务器
                server.start();
                System.out.println("多客户端SECS服务器已启动，监听地址: " + SERVER_IP + ":" + SERVER_PORT);
                System.out.println("服务器将持续运行，等待客户端连接...");
                System.out.println("输入命令 (help 查看帮助):");

                // 命令行交互
                Scanner scanner = new Scanner(System.in);
                boolean running = true;

                while (running && server.isRunning()) {
                    System.out.print("> ");
                    String command = scanner.nextLine().trim();

                    switch (command.toLowerCase()) {
                        case "help":
                            printHelp();
                            break;
                        case "list":
                            listClients(server);
                            break;
                        case "broadcast":
                            broadcastMessage(server, scanner);
                            break;
                        case "send":
                            sendMessage(server, scanner);
                            break;
                        case "status":
                            printStatus(server);
                            break;
                        case "exit":
                        case "quit":
                            running = false;
                            break;
                        default:
                            System.out.println("未知命令，输入 'help' 查看帮助");
                    }
                }

                System.out.println("正在关闭服务器...");
            }

            System.out.println("服务器已安全关闭");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "服务器运行时发生异常: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }

    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("可用命令:");
        System.out.println("  help      - 显示帮助信息");
        System.out.println("  list      - 列出所有连接的客户端");
        System.out.println("  broadcast - 向所有客户端广播消息");
        System.out.println("  send      - 向特定客户端发送消息");
        System.out.println("  status    - 显示服务器状态");
        System.out.println("  exit/quit - 退出程序");
    }

    /**
     * 列出所有客户端
     *
     * @param server 服务器
     */
    private static void listClients(MultiClientSecsServer server) {
        System.out.println("连接的客户端列表:");
        int count = 0;

        for (ClientConnection connection : server.getAllClientConnections()) {
            System.out.println("  " + (++count) + ". " + connection.getRemoteAddress() +
                    " (连接时间: " + formatTime(System.currentTimeMillis() - connection.getCreationTime()) + ")");
        }

        if (count == 0) {
            System.out.println("  没有连接的客户端");
        }
    }

    /**
     * 广播消息
     *
     * @param server 服务器
     * @param scanner 扫描器
     */
    private static void broadcastMessage(MultiClientSecsServer server, Scanner scanner) {
        try {
            System.out.print("输入广播消息: ");
            String message = scanner.nextLine();

            if (!message.isEmpty()) {
                Secs2 data = Secs2.ascii(message);
                server.broadcastToAllClients(9, 1, false, data);
                System.out.println("消息已广播到所有客户端");
            }
        } catch (Exception e) {
            System.out.println("广播消息失败: " + e.getMessage());
        }
    }

    /**
     * 向特定客户端发送消息
     *
     * @param server 服务器
     * @param scanner 扫描器
     */
    private static void sendMessage(MultiClientSecsServer server, Scanner scanner) {
        try {
            // 列出客户端
            System.out.println("选择客户端:");
            int count = 0;
            ClientConnection[] connections = server.getAllClientConnections().toArray(new ClientConnection[0]);

            for (ClientConnection connection : connections) {
                System.out.println("  " + (++count) + ". " + connection.getRemoteAddress());
            }

            if (count == 0) {
                System.out.println("  没有连接的客户端");
                return;
            }

            // 选择客户端
            System.out.print("输入客户端编号: ");
            int clientIndex = Integer.parseInt(scanner.nextLine().trim());

            if (clientIndex < 1 || clientIndex > count) {
                System.out.println("无效的客户端编号");
                return;
            }

            ClientConnection selectedClient = connections[clientIndex - 1];

            // 输入消息
            System.out.print("输入消息: ");
            String message = scanner.nextLine();

            if (!message.isEmpty()) {
                Secs2 data = Secs2.ascii(message);
                selectedClient.send(9, 1, false, data);
                System.out.println("消息已发送到客户端: " + selectedClient.getRemoteAddress());
            }
        } catch (NumberFormatException e) {
            System.out.println("无效的客户端编号");
        } catch (Exception e) {
            System.out.println("发送消息失败: " + e.getMessage());
        }
    }

    /**
     * 打印服务器状态
     *
     * @param server 服务器
     */
    private static void printStatus(MultiClientSecsServer server) {
        System.out.println("服务器状态:");
        System.out.println("  运行状态: " + (server.isRunning() ? "运行中" : "已停止"));
        System.out.println("  监听地址: " + SERVER_IP + ":" + SERVER_PORT);
        System.out.println("  客户端数: " + server.getClientCount());
        System.out.println("  设备ID: " + DEVICE_ID);
        System.out.println("  心跳间隔: " + formatTime(HEARTBEAT_INTERVAL));
        System.out.println("  连接超时: " + formatTime(CONNECTION_TIMEOUT));
    }

    /**
     * 处理消息
     *
     * @param server 服务器
     * @param message 消息
     * @param sourceAddress 来源地址
     */
    private static void handleCustomMessage(MultiClientSecsServer server, SecsMessage message,
                                           SocketAddress sourceAddress) {
        try {
            int stream = message.getStream();
            int function = message.getFunction();
            boolean wbit = message.wbit();

            // 处理标准SECS消息
            if (stream == 1) {
                // Stream 1 - 设备信息相关
                switch (function) {
                    case 1: {
                        // S1F1 W - Are You There
                        if (wbit) {
                            // 回复S1F2 - On Line Data
                            Secs2 reply = Secs2.list(
                                Secs2.ascii("MULTI-CLIENT-SERVER"),  // 设备型号
                                Secs2.ascii("1.0.0")               // 软件版本
                            );

                            server.sendToClient(sourceAddress, 1, 2, false, reply);
                            logger.info("发送S1F2回复到客户端: " + sourceAddress);
                            System.out.println("发送S1F2回复到客户端: " + sourceAddress);
                        }
                        break;
                    }
                    case 13: {
                        // S1F13 W - 建立通信请求
                        if (wbit) {
                            // 回复S1F14 - 建立通信确认
                            server.sendToClient(sourceAddress, 1, 14, false, Secs2.binary((byte)0x00));
                            logger.info("发送S1F14回复到客户端: " + sourceAddress);
                            System.out.println("发送S1F14回复到客户端: " + sourceAddress);
                        }
                        break;
                    }
                }
            } else if (stream == 5) {
                // Stream 5 - 链路测试相关
                switch (function) {
                    case 5: {
                        // S5F5 W - 链路测试请求
                        if (wbit) {
                            // 回复S5F6 - 链路测试确认
                            server.sendToClient(sourceAddress, 5, 6, false, Secs2.empty());
                            logger.info("发送S5F6回复到客户端: " + sourceAddress);
                            System.out.println("发送S5F6回复到客户端: " + sourceAddress);
                        }
                        break;
                    }
                }
            }

            // 处理自定义消息 (S9Fx)
            if (stream == 9) {
                switch (function) {
                    case 1: {
                        // S9F1 - 自定义消息
                        logger.info("收到来自 " + sourceAddress + " 的自定义消息: " + message.secs2());

                        if (wbit) {
                            // 如果需要回复，发送S9F2
                            Secs2 reply = Secs2.ascii("收到自定义消息");
                            server.sendToClient(sourceAddress, 9, 2, false, reply);
                            logger.info("发送S9F2回复到客户端: " + sourceAddress);
                        }
                        break;
                    }
                    case 3: {
                        // S9F3 - 回显请求
                        if (wbit) {
                            // 回显消息
                            Secs2 data = message.secs2();
                            server.sendToClient(sourceAddress, 9, 4, false, data);
                            logger.info("回显消息到客户端: " + sourceAddress);
                        }
                        break;
                    }
                    case 5: {
                        // S9F5 - 广播请求
                        if (wbit) {
                            try {
                                // 解析广播消息
                                Secs2 data = message.secs2();
                                String broadcastMessage = data.getAscii();

                                // 广播到所有客户端
                                Secs2 broadcastData = Secs2.list(
                                    Secs2.ascii("BROADCAST"),
                                    Secs2.ascii(broadcastMessage)
                                );

                                server.broadcastToAllClients(9, 9, false, broadcastData);
                                logger.info("广播消息到所有客户端: " + broadcastMessage);

                                // 回复S9F6
                                server.sendToClient(sourceAddress, 9, 6, false, Secs2.binary((byte)0x00));
                                logger.info("发送S9F6回复到客户端: " + sourceAddress);
                            } catch (Exception e) {
                                // 广播失败
                                server.sendToClient(sourceAddress, 9, 6, false, Secs2.binary((byte)0x01));
                                logger.warning("发送S9F6回复到客户端: " + sourceAddress + ", 广播失败");
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "处理自定义消息时发生异常: " + e.getMessage(), e);
        }
    }

    /**
     * 格式化时间
     *
     * @param millis 毫秒
     * @return 格式化后的时间字符串
     */
    private static String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%d天 %02d:%02d:%02d", days, hours % 24, minutes % 60, seconds % 60);
        } else if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%02d:%02d", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }
}
