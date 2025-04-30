package com.shimizukenta.tmp.secs;

import com.shimizukenta.secs.secs2.Secs2;
import com.youibot.tms.secs.SecsServer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 优化的SECS-I/HSMS-SS服务器使用示例
 */
public class SecsServerExampleV2 {
    private static final Logger LOGGER = Logger.getLogger(SecsServerExampleV2.class.getName());

    public static void main(String[] args) {
        try {
            // 创建SECS-I模式服务器（端口9000，最大10个连接，超时时间30秒）
//            SecsServer secsIServer = new SecsServer(5000, 10, 30000);

            // 或者创建HSMS-SS模式服务器（端口5000，最大10个连接，超时时间30秒，使用HSMS=true）
            SecsServer hsmsServer = new SecsServer(5000, 10, 30000, true);

            // 选择一个服务器实例运行
            SecsServer server = hsmsServer; // 可以切换到secsIServer

            // 设置消息处理器
            server.setMessageHandler(new ExampleMessageHandler(server));

            // 设置连接事件监听器
            server.setConnectionEventListener(new ExampleConnectionListener());

            // 启动服务器
            server.start();

            LOGGER.info("SECS Server is running. Press Enter to stop.");

            // 等待用户输入以停止服务器
            CountDownLatch latch = new CountDownLatch(1);
            new Thread(() -> {
                try {
                    System.in.read();
                    latch.countDown();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();

            latch.await();

            // 停止服务器
            server.stop();
            LOGGER.info("Server stopped.");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error running server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 示例消息处理器
     */
    static class ExampleMessageHandler implements SecsServer.SecsMessageHandler {

        // 保存对服务器实例的引用
        private final SecsServer server;

        public ExampleMessageHandler(SecsServer server) {
            this.server = server;
        }

        @Override
        public void onMessage(int clientId, int stream, int function, byte[] systemBytes, byte[] data) {
            LOGGER.info(String.format("Received message from client %d: S%dF%d, System bytes: %s, Data length: %d",
                    clientId, stream, function, bytesToHex(systemBytes), data != null ? data.length : 0));

            // 根据流和功能码处理不同类型的消息
            switch (stream) {
                case 1:
                    handleStream1(clientId, function, systemBytes, data);
                    break;
                case 2:
                    handleStream2(clientId, function, systemBytes, data);
                    break;
                default:
                    LOGGER.warning("Unhandled stream: " + stream);
            }
        }

        private void handleStream1(int clientId, int function, byte[] systemBytes, byte[] data) {
            // 处理Stream 1的消息
            switch (function) {
                case 1: // S1F1 - 设备状态请求
                    LOGGER.info("Received S1F1 - Are You There");
                    // 发送S1F2响应（在线状态）
                    try {
                        // 使用Secs2创建响应数据 - MDLN和SOFTREV
                        Secs2 responseData = Secs2.list(
                            Secs2.ascii("YOUIBOT-AGVSA"),  // MDLN - 与客户端日志中的设备名称保持一致
                            Secs2.ascii("1.0.0")          // SOFTREV
                        );
                        // 发送S1F2响应，不需要设置W-bit，使用原始系统字节
                        LOGGER.warning("CRITICAL: About to send S1F2 with SystemBytes: " + SecsServer.bytesToHex(systemBytes));

                        // 不需要手动设置SessionID，因为我们已经在sendHsmsMessageV2方法中处理了

                        server.sendMessage(clientId, 1, 2, false, systemBytes, responseData);
                        LOGGER.warning("CRITICAL: S1F2 sent successfully");
                        LOGGER.info("Sent S1F2 - Online Data");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S1F2 response", e);
                    }
                    break;

                case 3: // S1F3 - Selected Equipment Status Request
                    LOGGER.info("Received S1F3 - Selected Equipment Status Request");
                    // 发送S1F4响应（设备状态数据）
                    try {
                        // 创建状态响应数据
                        // 通常是一个列表，包含多个状态值
                        // 简化处理，直接返回三个状态值
                        Secs2 statusData = Secs2.list(
                            Secs2.ascii("REMOTE"),      // 控制状态 - 远程控制
                            Secs2.ascii("IDLE"),        // 运行状态 - 空闲
                            Secs2.ascii("COMMUNICATING") // 通信状态 - 通信中
                        );

                        // 发送S1F4响应，使用原始系统字节
                        server.sendMessage(clientId, 1, 4, false, systemBytes, statusData);
                        LOGGER.info("Sent S1F4 - Selected Equipment Status Data: " + statusData);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S1F4 response", e);
                    }
                    break;

                case 13: // S1F13 - 建立通信请求
                    LOGGER.info("Received S1F13 - Establish Communication Request");
                    // 发送S1F14响应（通信已建立）
                    try {
                        // 使用Secs2创建响应数据 - COMMACK和版本信息
                        Secs2 responseData = Secs2.binary((byte)0); // 0 = 成功建立通信
                        server.sendMessage(clientId, 1, 14, false, systemBytes, responseData);
                        LOGGER.info("Sent S1F14 - Communication Established");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S1F14 response", e);
                    }
                    break;

                case 15: // S1F15 - 请求离线
                    LOGGER.info("Received S1F15 - Request Offline");
                    // 发送S1F16响应（离线已确认）
                    try {
                        // 使用Secs2创建响应数据 - OFLACK
                        Secs2 responseData = Secs2.binary((byte)0); // 0 = 允许离线
                        server.sendMessage(clientId, 1, 16, false, systemBytes, responseData);
                        LOGGER.info("Sent S1F16 - Offline Acknowledged");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S1F16 response", e);
                    }
                    break;

                case 17: // S1F17 - 请求在线
                    LOGGER.info("Received S1F17 - Request Online");
                    // 发送S1F18响应（在线已确认）
                    try {
                        // 使用Secs2创建响应数据 - ONLACK
                        Secs2 responseData = Secs2.binary((byte)0); // 0 = 允许在线
                        server.sendMessage(clientId, 1, 18, false, systemBytes, responseData);
                        LOGGER.info("Sent S1F18 - Online Acknowledged");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S1F18 response", e);
                    }
                    break;

                default:
                    LOGGER.warning("Unhandled S1F" + function + " message");
                    break;
            }
        }

        private void handleStream2(int clientId, int function, byte[] systemBytes, byte[] data) {
            // 处理Stream 2的消息
            switch (function) {
                case 31: // S2F31 - 时间数据请求
                    LOGGER.info("Received S2F31 - Date and Time Request");
                    // 发送S2F32响应（时间数据）
                    try {
                        // 使用Secs2创建当前时间响应数据
                        // 获取当前时间
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        String timeStr = String.format("%02d%02d%02d%02d%02d%02d",
                                cal.get(java.util.Calendar.YEAR) % 100,
                                cal.get(java.util.Calendar.MONTH) + 1,
                                cal.get(java.util.Calendar.DAY_OF_MONTH),
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE),
                                cal.get(java.util.Calendar.SECOND));
                        Secs2 responseData = Secs2.ascii(timeStr);
                        server.sendMessage(clientId, 2, 32, false, systemBytes, responseData);
                        LOGGER.info("Sent S2F32 - Date and Time Data");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S2F32 response", e);
                    }
                    break;

                case 33: // S2F33 - 定义报告
                    LOGGER.info("Received S2F33 - Define Report");
                    // 发送S2F34响应（报告定义确认）
                    try {
                        // 使用Secs2创建响应数据 - DRACK
                        Secs2 responseData = Secs2.binary((byte)0); // 0 = 成功接受报告
                        server.sendMessage(clientId, 2, 34, false, systemBytes, responseData);
                        LOGGER.info("Sent S2F34 - Define Report Acknowledge");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S2F34 response", e);
                    }
                    break;

                case 35: // S2F35 - 链接报告
                    LOGGER.info("Received S2F35 - Link Report");
                    // 发送S2F36响应（链接报告确认）
                    try {
                        // 使用Secs2创建响应数据 - LRACK
                        Secs2 responseData = Secs2.binary((byte)0); // 0 = 成功接受链接
                        server.sendMessage(clientId, 2, 36, false, systemBytes, responseData);
                        LOGGER.info("Sent S2F36 - Link Report Acknowledge");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S2F36 response", e);
                    }
                    break;

                case 37: // S2F37 - 禁用所有事件报告
                    LOGGER.info("Received S2F37 - Disable All Event Reports");
                    // 发送S2F38响应（禁用确认）
                    try {
                        // 使用Secs2创建响应数据 - ERACK
                        Secs2 responseData = Secs2.binary((byte)0); // 0 = 已接受
                        server.sendMessage(clientId, 2, 38, false, systemBytes, responseData);
                        LOGGER.info("Sent S2F38 - Disable Event Report Acknowledge");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S2F38 response", e);
                    }
                    break;

                case 39: // S2F39 - 启用所有事件报告
                    LOGGER.info("Received S2F39 - Enable All Event Reports");
                    // 发送S2F40响应（启用确认）
                    try {
                        // 使用Secs2创建响应数据 - ERACK
                        Secs2 responseData = Secs2.binary((byte)0); // 0 = 已接受
                        server.sendMessage(clientId, 2, 40, false, systemBytes, responseData);
                        LOGGER.info("Sent S2F40 - Enable Event Report Acknowledge");
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error sending S2F40 response", e);
                    }
                    break;

                default:
                    LOGGER.warning("Unhandled S2F" + function + " message");
                    break;
            }
        }





        // 辅助方法：将字节数组转为十六进制字符串
        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    /**
     * 示例连接事件监听器
     */
    static class ExampleConnectionListener implements SecsServer.ConnectionEventListener {
        @Override
        public void onClientConnected(int clientId, String address) {
            LOGGER.info("Client " + clientId + " connected from " + address);
            System.out.println("Client " + clientId + " connected from " + address);
        }

        @Override
        public void onClientDisconnected(int clientId) {
            LOGGER.info("Client " + clientId + " disconnected");
            System.out.println("Client " + clientId + " disconnected");
        }
    }
}