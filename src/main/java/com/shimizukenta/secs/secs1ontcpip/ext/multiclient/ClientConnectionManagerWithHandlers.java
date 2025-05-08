package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 带有连接和断开连接处理器的客户端连接管理器
 */
public class ClientConnectionManagerWithHandlers extends ClientConnectionManager {

    private static final Logger logger = Logger.getLogger(ClientConnectionManagerWithHandlers.class.getName());

    private final Consumer<ClientConnection> clientDisconnectedHandler;

    /**
     * 构造函数
     *
     * @param config 通信器配置
     * @param heartbeatInterval 心跳间隔（毫秒）
     * @param connectionTimeout 连接超时（毫秒）
     * @param clientDisconnectedHandler 客户端断开连接处理器
     */
    public ClientConnectionManagerWithHandlers(
            Secs1OnTcpIpReceiverCommunicatorConfig config,
            long heartbeatInterval,
            long connectionTimeout,
            Consumer<ClientConnection> clientDisconnectedHandler) {
        super(config, heartbeatInterval, connectionTimeout);
        this.clientDisconnectedHandler = clientDisconnectedHandler;
    }

    /**
     * 移除客户端连接
     *
     * @param remoteAddress 客户端地址
     */
    @Override
    public void removeConnection(SocketAddress remoteAddress) {
        ClientConnection connection = getConnection(remoteAddress);
        
        // 先调用断开连接处理器
        if (connection != null && clientDisconnectedHandler != null) {
            try {
                logger.info("调用客户端断开连接处理器: " + remoteAddress);
                System.out.println("调用客户端断开连接处理器: " + remoteAddress);
                clientDisconnectedHandler.accept(connection);
            } catch (Exception e) {
                logger.log(Level.WARNING, "调用客户端断开连接处理器失败: " + e.getMessage(), e);
                System.out.println("调用客户端断开连接处理器失败: " + e.getMessage());
            }
        }
        
        // 然后调用父类的移除方法
        super.removeConnection(remoteAddress);
    }
}
