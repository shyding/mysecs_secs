package com.shimizukenta;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Scanner;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * SECS客户端测试类
 * 用于测试与修复后的SecsServer的通信
 */
public class SecsClientTest {

    private static final Logger LOGGER = Logger.getLogger(SecsClientTest.class.getName());
    
    // 服务器配置
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 5000;
    
    // SECS-I协议常量
    private static final byte ENQ = 0x05;
    private static final byte EOT = 0x04;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;
    
    // 客户端状态
    private static Socket socket;
    private static InputStream inputStream;
    private static OutputStream outputStream;
    private static boolean connected = false;
    
    public static void main(String[] args) {
        // 配置日志
        configureLogging();
        
        try {
            // 显示命令菜单
            showMenu();
            
            // 处理用户输入
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("> ");
                    String input = scanner.nextLine().trim();
                    
                    if (input.isEmpty()) {
                        continue;
                    }
                    
                    if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                        break;
                    } else if (input.equalsIgnoreCase("help") || input.equals("?")) {
                        showMenu();
                    } else if (input.equalsIgnoreCase("connect")) {
                        connect();
                    } else if (input.equalsIgnoreCase("disconnect")) {
                        disconnect();
                    } else if (input.equalsIgnoreCase("s1f1")) {
                        sendS1F1();
                    } else if (input.startsWith("send ")) {
                        String message = input.substring("send ".length()).trim();
                        sendCustomMessage(message);
                    } else {
                        System.out.println("未知命令: " + input);
                        showMenu();
                    }
                }
            }
            
            // 断开连接
            disconnect();
            
        } catch (Exception e) {
            LOGGER.severe("客户端错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 配置日志
     */
    private static void configureLogging() {
        // 设置根日志级别
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.INFO);
        
        // 设置控制台处理器
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        handler.setFormatter(new SimpleFormatter());
        
        // 添加处理器
        rootLogger.addHandler(handler);
    }
    
    /**
     * 显示命令菜单
     */
    private static void showMenu() {
        System.out.println("\n可用命令:");
        System.out.println("  help, ?    - 显示此帮助信息");
        System.out.println("  connect    - 连接到服务器");
        System.out.println("  disconnect - 断开连接");
        System.out.println("  s1f1       - 发送S1F1 (Are You There)消息");
        System.out.println("  send <消息> - 发送自定义消息");
        System.out.println("  exit, quit - 退出程序");
        System.out.println();
    }
    
    /**
     * 连接到服务器
     */
    private static void connect() {
        if (connected) {
            System.out.println("已经连接到服务器");
            return;
        }
        
        try {
            // 创建套接字
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            connected = true;
            
            // 启动接收线程
            new Thread(SecsClientTest::receiveLoop, "SECS-Client-Receiver").start();
            
            System.out.println("已连接到服务器: " + SERVER_HOST + ":" + SERVER_PORT);
            LOGGER.info("已连接到服务器: " + SERVER_HOST + ":" + SERVER_PORT);
        } catch (IOException e) {
            System.out.println("连接失败: " + e.getMessage());
            LOGGER.log(Level.SEVERE, "连接失败", e);
        }
    }
    
    /**
     * 断开连接
     */
    private static void disconnect() {
        if (!connected) {
            System.out.println("未连接到服务器");
            return;
        }
        
        try {
            connected = false;
            
            if (socket != null) {
                socket.close();
                socket = null;
            }
            
            System.out.println("已断开连接");
            LOGGER.info("已断开连接");
        } catch (IOException e) {
            System.out.println("断开连接时发生错误: " + e.getMessage());
            LOGGER.log(Level.SEVERE, "断开连接时发生错误", e);
        }
    }
    
    /**
     * 发送S1F1 (Are You There)消息
     */
    private static void sendS1F1() {
        if (!connected) {
            System.out.println("未连接到服务器，请先连接");
            return;
        }
        
        try {
            // 构建S1F1消息
            byte[] message = buildSecsMessage(1, 1, new byte[4], null);
            
            // 发送消息
            outputStream.write(message);
            outputStream.flush();
            
            System.out.println("已发送S1F1消息");
            LOGGER.info("已发送S1F1消息");
            
            // 打印消息内容
            StringBuilder sb = new StringBuilder("消息内容: ");
            for (byte b : message) {
                sb.append(String.format("%02X ", b));
            }
            LOGGER.fine(sb.toString());
        } catch (IOException e) {
            System.out.println("发送S1F1消息失败: " + e.getMessage());
            LOGGER.log(Level.SEVERE, "发送S1F1消息失败", e);
            disconnect();
        }
    }
    
    /**
     * 发送自定义消息
     * 
     * @param message 消息内容
     */
    private static void sendCustomMessage(String message) {
        if (!connected) {
            System.out.println("未连接到服务器，请先连接");
            return;
        }
        
        try {
            // 构建S9F1消息（自定义消息）
            byte[] data = message.getBytes();
            byte[] secsMessage = buildSecsMessage(9, 1, new byte[4], data);
            
            // 发送消息
            outputStream.write(secsMessage);
            outputStream.flush();
            
            System.out.println("已发送自定义消息: " + message);
            LOGGER.info("已发送自定义消息: " + message);
            
            // 打印消息内容
            StringBuilder sb = new StringBuilder("消息内容: ");
            for (int i = 0; i < Math.min(secsMessage.length, 32); i++) {
                sb.append(String.format("%02X ", secsMessage[i]));
            }
            if (secsMessage.length > 32) {
                sb.append("...");
            }
            LOGGER.fine(sb.toString());
        } catch (IOException e) {
            System.out.println("发送自定义消息失败: " + e.getMessage());
            LOGGER.log(Level.SEVERE, "发送自定义消息失败", e);
            disconnect();
        }
    }
    
    /**
     * 接收循环
     */
    private static void receiveLoop() {
        byte[] buffer = new byte[4096];
        
        while (connected) {
            try {
                // 读取数据
                int bytesRead = inputStream.read(buffer);
                
                if (bytesRead == -1) {
                    // 连接已关闭
                    System.out.println("服务器关闭了连接");
                    LOGGER.info("服务器关闭了连接");
                    disconnect();
                    break;
                }
                
                if (bytesRead > 0) {
                    // 处理接收到的数据
                    handleReceivedData(buffer, bytesRead);
                }
            } catch (IOException e) {
                if (connected) {
                    System.out.println("接收数据时发生错误: " + e.getMessage());
                    LOGGER.log(Level.SEVERE, "接收数据时发生错误", e);
                    disconnect();
                }
                break;
            }
        }
    }
    
    /**
     * 处理接收到的数据
     * 
     * @param buffer 数据缓冲区
     * @param length 数据长度
     */
    private static void handleReceivedData(byte[] buffer, int length) {
        // 打印接收到的数据
        StringBuilder sb = new StringBuilder("接收到数据: ");
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", buffer[i]));
        }
        LOGGER.fine(sb.toString());
        
        // 检查是否是单字节控制字符
        if (length == 1) {
            byte b = buffer[0];
            if (b == ACK) {
                System.out.println("接收到ACK");
                LOGGER.info("接收到ACK");
                return;
            } else if (b == NAK) {
                System.out.println("接收到NAK");
                LOGGER.info("接收到NAK");
                return;
            } else if (b == ENQ) {
                System.out.println("接收到ENQ");
                LOGGER.info("接收到ENQ");
                return;
            } else if (b == EOT) {
                System.out.println("接收到EOT");
                LOGGER.info("接收到EOT");
                return;
            }
        }
        
        // 尝试解析SECS消息
        if (length >= 12 && buffer[0] == ENQ && buffer[length - 1] == EOT) { // 至少包含ENQ、头部和EOT
            try {
                // 解析流和功能码
                byte streamByte = buffer[8];
                byte functionByte = buffer[9];
                int stream = streamByte & 0xFF;
                int function = functionByte & 0xFF;
                
                System.out.println(String.format("接收到S%dF%d消息", stream, function));
                LOGGER.info(String.format("接收到S%dF%d消息", stream, function));
                
                // 如果是S1F2（对S1F1的响应）
                if (stream == 1 && function == 2) {
                    System.out.println("接收到S1F2 (On Line Data)响应，服务器在线");
                }
            } catch (Exception e) {
                System.out.println("解析SECS消息失败: " + e.getMessage());
                LOGGER.log(Level.WARNING, "解析SECS消息失败", e);
            }
        }
    }
    
    /**
     * 构建SECS-I消息
     * 
     * @param stream 流ID
     * @param function 功能码
     * @param systemBytes 系统字节
     * @param data 消息数据
     * @return 完整的SECS-I消息字节数组
     */
    private static byte[] buildSecsMessage(int stream, int function, byte[] systemBytes, byte[] data) {
        // 确保systemBytes长度为4
        byte[] sysBytes = new byte[4];
        if (systemBytes != null && systemBytes.length > 0) {
            System.arraycopy(systemBytes, 0, sysBytes, 0, Math.min(systemBytes.length, 4));
        }
        
        // 计算消息长度（包括头部）
        int dataLength = (data != null) ? data.length : 0;
        int headerSize = 10; // SECS-I头部大小
        int totalLength = headerSize + dataLength;
        
        // 创建消息缓冲区
        byte[] message = new byte[totalLength + 2]; // +2为ENQ和EOT
        
        // 添加ENQ
        message[0] = ENQ;
        
        // 构建SECS头
        int pos = 1;
        message[pos++] = (byte) ((totalLength - 2) >> 8); // 长度高位
        message[pos++] = (byte) ((totalLength - 2) & 0xFF); // 长度低位
        message[pos++] = (byte) 0; // 设备ID高位
        message[pos++] = (byte) 1; // 设备ID低位
        
        // 系统字节
        for (int i = 0; i < 4; i++) {
            message[pos++] = sysBytes[i];
        }
        
        // 流和功能码
        message[pos++] = (byte) stream;
        message[pos++] = (byte) function;
        
        // 添加数据部分
        if (data != null && data.length > 0) {
            System.arraycopy(data, 0, message, pos, data.length);
            pos += data.length;
        }
        
        // 添加EOT
        message[pos] = EOT;
        
        return message;
    }
}
