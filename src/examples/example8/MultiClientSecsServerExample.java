package example8;

import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs1ontcpip.ext.multiclient.ClientConnection;
import com.shimizukenta.secs.secs1ontcpip.ext.multiclient.MultiClientSecsServer;
import com.shimizukenta.secs.secs2.Secs2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 多客户端SECS服务器示例
 */
public class MultiClientSecsServerExample {
    
    private static final Logger logger = Logger.getLogger(MultiClientSecsServerExample.class.getName());
    
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
            config.timeout().t1(1.0F);     // ENQ超时
            config.timeout().t2(45.0F);    // 回复超时
            config.timeout().t3(90.0F);    // 发送超时
            config.timeout().t4(90.0F);    // 中间响应超时
            
            // 设置重试次数
            config.retry(3);
            
            // 创建多客户端SECS服务器
            try (MultiClientSecsServer server = new MultiClientSecsServer(
                    config, SERVER_IP, SERVER_PORT, HEARTBEAT_INTERVAL, CONNECTION_TIMEOUT)) {
                
                // 设置客户端连接处理器
                server.setClientConnectedHandler(connection -> {
                    System.out.println("客户端已连接: " + connection.getRemoteAddress());
                    
                    // 发送欢迎消息
                    try {
                        Secs2 welcomeData = Secs2.ascii("Welcome to Multi-Client SECS Server!");
                        connection.send(9, 1, false, welcomeData);
                        System.out.println("发送欢迎消息到客户端: " + connection.getRemoteAddress());
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "发送欢迎消息失败: " + e.getMessage(), e);
                    }
                });
                
                // 设置客户端断开连接处理器
                server.setClientDisconnectedHandler(connection -> {
                    System.out.println("客户端已断开连接: " + connection.getRemoteAddress());
                });
                
                // 设置消息接收处理器
                server.setMessageReceivedHandler((message, sourceAddress) -> {
                    System.out.println(String.format("收到来自 %s 的消息: S%dF%d%s", 
                            sourceAddress, message.getStream(), message.getFunction(), 
                            (message.wbit() ? " W" : "")));
                    
                    // 处理S1F1消息（Are You There）
                    if (message.getStream() == 1 && message.getFunction() == 1 && message.wbit()) {
                        try {
                            // 回复S1F2（On Line Data）
                            Secs2 reply = Secs2.list(
                                Secs2.ascii("MULTI-CLIENT-SERVER"),  // 设备型号
                                Secs2.ascii("1.0.0")     // 软件版本
                            );
                            
                            server.sendToClient(sourceAddress, 1, 2, false, reply);
                            System.out.println("发送S1F2回复到客户端: " + sourceAddress);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "发送S1F2回复失败: " + e.getMessage(), e);
                        }
                    }
                });
                
                // 启动服务器
                server.start();
                System.out.println("多客户端SECS服务器已启动，监听地址: " + SERVER_IP + ":" + SERVER_PORT);
                System.out.println("服务器将持续运行，等待客户端连接...");
                System.out.println("按Enter键停止服务器");
                
                // 等待用户输入
                new Scanner(System.in).nextLine();
                
                System.out.println("正在关闭服务器...");
            }
            
            System.out.println("服务器已安全关闭");
            
        } catch (IOException e) {
            logger.log(Level.SEVERE, "服务器运行时发生异常: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }
}
