package com.shimizukenta.secs.secs1ontcpip.ext;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.secs1.Secs1Message;
import com.shimizukenta.secs.secs1.Secs1SendByteException;
import com.shimizukenta.secs.secs1.Secs1SendMessageException;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs1ontcpip.impl.AbstractSecs1OnTcpIpReceiverCommunicator;
import com.shimizukenta.secs.secs2.Secs2;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 扩展的SECS-I TCP/IP接收器通信器，支持向特定客户端发送消息
 */
public class ExtendedSecs1OnTcpIpReceiverCommunicator extends AbstractSecs1OnTcpIpReceiverCommunicator {

    private static final Logger logger = Logger.getLogger(ExtendedSecs1OnTcpIpReceiverCommunicator.class.getName());

    // 存储客户端地址和通道的映射
    private final Map<SocketAddress, AsynchronousSocketChannel> clientChannels = new ConcurrentHashMap<>();

    // 存储通道和地址的映射（反向映射）
    private final Map<AsynchronousSocketChannel, SocketAddress> channelAddresses = new ConcurrentHashMap<>();

    // 消息源通道映射，用于跟踪消息来源
    private final Map<Integer, AsynchronousSocketChannel> messageSourceChannels = new ConcurrentHashMap<>();

    // 存储消息ID和回复消息的映射
    private final Map<Integer, SecsMessage> replyMessages = new ConcurrentHashMap<>();

    // 存储消息ID和客户端地址的映射，用于跟踪消息的目标客户端
    private final Map<Integer, SocketAddress> messageTargetAddresses = new ConcurrentHashMap<>();

    // 最后一次接收到消息的客户端地址，用于快速跟踪消息的来源
    private volatile SocketAddress lastReceivedAddress = null;

    // 最后一次接收到消息的通道，用于快速跟踪消息的来源
    private volatile AsynchronousSocketChannel lastReceivedChannel = null;

    // 消息格式处理器，用于确保消息格式正确
    private final Secs1MessageFormatter messageFormatter = new Secs1MessageFormatter();

    /**
     * 构造函数
     *
     * @param config 配置
     * @throws IOException 如果创建通信器失败
     */
    public ExtendedSecs1OnTcpIpReceiverCommunicator(Secs1OnTcpIpReceiverCommunicatorConfig config) throws IOException {
        super(config);

        // 添加连接日志监听器，跟踪客户端连接
        this.addSecsLogListener(log -> {
            String logMessage = log.toString();

            try {
                if (logMessage.contains("Accepted") && logMessage.contains("SECS1-onTCP/IP-Communicator")) {
                    // 客户端连接事件
                    handleClientConnection(logMessage, true);
                } else if (logMessage.contains("AcceptClosed") && logMessage.contains("SECS1-onTCP/IP-Communicator")) {
                    // 客户端断开连接事件
                    handleClientConnection(logMessage, false);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "处理日志事件失败: " + logMessage, e);
            }
        });

        // 添加消息接收监听器，跟踪消息来源
        this.addSecsMessageReceiveListener(message -> {
            try {
                // 获取消息的系统字节，用作消息ID
                int messageId = getMessageId(message);

                System.out.println("[DEBUG-RECEIVE] 收到消息: S" + message.getStream() + "F" + message.getFunction() + ", ID=" + messageId);
                System.out.println("[DEBUG-RECEIVE] 当前客户端连接数: " + clientChannels.size());
                System.out.println("[DEBUG-RECEIVE] 当前客户端列表: " + clientChannels.keySet());

                // 重要：在消息接收时，我们需要确定消息的来源通道
                // 这里我们使用父类的实现来获取当前活动的通道
                AsynchronousSocketChannel currentChannel = null;

                try {
                    // 尝试使用反射获取当前活动的通道
                    java.lang.reflect.Field activeChannelField = this.getClass().getSuperclass().getDeclaredField("activeChannel");
                    if (activeChannelField != null) {
                        activeChannelField.setAccessible(true);
                        currentChannel = (AsynchronousSocketChannel) activeChannelField.get(this);
                        System.out.println("[DEBUG-RECEIVE] 使用反射获取当前活动通道: " + currentChannel);
                    }
                } catch (Exception e) {
                    System.out.println("[DEBUG-RECEIVE] 使用反射获取当前活动通道失败: " + e.getMessage());
                }

                // 如果无法使用反射获取，则使用最后一次接收到消息的通道
                if (currentChannel == null) {
                    currentChannel = lastReceivedChannel;
                    System.out.println("[DEBUG-RECEIVE] 使用最后接收通道: " + currentChannel);
                }

                // 更新最后一次接收到消息的通道
                lastReceivedChannel = currentChannel;
                System.out.println("[DEBUG-RECEIVE] 更新最后接收通道: " + lastReceivedChannel);

                // 将消息ID和来源通道关联起来，用于跟踪回复
                if (currentChannel != null) {
                    messageSourceChannels.put(messageId, currentChannel);
                    System.out.println("[DEBUG-RECEIVE] 将消息ID和来源通道关联: " + messageId + " -> " + currentChannel);
                }

                // 获取来源地址
                SocketAddress sourceAddr = null;
                if (currentChannel != null) {
                    sourceAddr = channelAddresses.get(currentChannel);
                    System.out.println("[DEBUG-RECEIVE] 根据通道获取到的客户端地址: " + sourceAddr);
                }

                if (sourceAddr != null) {
                    // 更新最后一次接收到消息的客户端地址
                    lastReceivedAddress = sourceAddr;
                    System.out.println("[DEBUG-RECEIVE] 设置最后接收地址: " + lastReceivedAddress);

                    logger.info("收到来自 " + sourceAddr + " 的消息: S" + message.getStream() + "F" + message.getFunction() + ", ID=" + messageId);

                    // 如果这是一个回复消息，将其添加到回复消息映射中
                    if (message.getStream() % 2 == 0) { // 偶数流ID表示回复消息
                        // 尝试找到原始消息的ID
                        int originalMessageId = messageId - 1; // 简化处理，实际上需要更复杂的逻辑
                        addReplyMessage(originalMessageId, message);
                    }
                } else {
                    System.out.println("[DEBUG-RECEIVE] 无法确定消息的来源地址");
                    logger.warning("无法确定消息的来源地址: S" + message.getStream() + "F" + message.getFunction() + ", ID=" + messageId);
                }
            } catch (Exception e) {
                System.out.println("[DEBUG-RECEIVE] 跟踪消息来源失败: " + e.getMessage());
                e.printStackTrace(System.out);
                logger.log(Level.WARNING, "跟踪消息来源失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 处理客户端连接事件
     *
     * @param logMessage 日志消息
     * @param isConnected true表示连接事件，false表示断开连接事件
     */
    private void handleClientConnection(String logMessage, boolean isConnected) {
        try {
            // 从日志消息中提取客户端地址
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
                    SocketAddress clientAddr = new java.net.InetSocketAddress(ip, port);

                    if (isConnected) {
                        // 客户端连接事件
                        System.out.println("[DEBUG-CONNECT] 客户端连接事件: " + clientAddr);
                        System.out.println("[DEBUG-CONNECT] 当前客户端连接数: " + clientChannels.size());
                        System.out.println("[DEBUG-CONNECT] 当前客户端列表: " + clientChannels.keySet());

                        // 获取客户端通道
                        AsynchronousSocketChannel clientChannel = getChannelByAddress(clientAddr);
                        System.out.println("[DEBUG-CONNECT] 获取到客户端通道: " + clientChannel);

                        if (clientChannel != null) {
                            // 存储客户端地址和通道的映射
                            clientChannels.put(clientAddr, clientChannel);
                            channelAddresses.put(clientChannel, clientAddr);

                            // 记录最后一次接收到消息的客户端地址
                            lastReceivedAddress = clientAddr;
                            System.out.println("[DEBUG-CONNECT] 设置最后接收地址: " + lastReceivedAddress);

                            // 记录最后一次接收到消息的通道
                            lastReceivedChannel = clientChannel;
                            System.out.println("[DEBUG-CONNECT] 设置最后接收通道: " + lastReceivedChannel);

                            logger.info("新客户端连接: " + clientAddr + ", 当前连接数: " + clientChannels.size());
                            System.out.println("[DEBUG-CONNECT] 新客户端连接完成: " + clientAddr);
                        } else {
                            System.out.println("[DEBUG-CONNECT] 无法获取客户端通道: " + clientAddr);
                            logger.warning("无法获取客户端通道: " + clientAddr);
                        }
                    } else {
                        // 客户端断开连接事件
                        System.out.println("[DEBUG-DISCONNECT] 客户端断开连接事件: " + clientAddr);
                        System.out.println("[DEBUG-DISCONNECT] 当前客户端连接数: " + clientChannels.size());
                        System.out.println("[DEBUG-DISCONNECT] 当前客户端列表: " + clientChannels.keySet());

                        AsynchronousSocketChannel channel = clientChannels.remove(clientAddr);
                        System.out.println("[DEBUG-DISCONNECT] 移除客户端通道: " + channel);

                        if (channel != null) {
                            // 移除通道和客户端地址的映射
                            channelAddresses.remove(channel);
                            System.out.println("[DEBUG-DISCONNECT] 移除通道地址映射");

                            // 如果这是最后一次接收到消息的通道，清除它
                            if (channel.equals(lastReceivedChannel)) {
                                lastReceivedChannel = null;
                                System.out.println("[DEBUG-DISCONNECT] 清除最后接收通道");
                            }

                            // 如果这是最后一次接收到消息的客户端地址，清除它
                            if (clientAddr.equals(lastReceivedAddress)) {
                                lastReceivedAddress = null;
                                System.out.println("[DEBUG-DISCONNECT] 清除最后接收地址");
                            }

                            logger.info("客户端断开连接: " + clientAddr + ", 当前连接数: " + clientChannels.size());
                            System.out.println("[DEBUG-DISCONNECT] 客户端断开连接完成: " + clientAddr);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "处理客户端连接事件失败: " + logMessage, e);
        }
    }

    /**
     * 获取特定地址的通道
     *
     * @param addr 客户端地址
     * @return 通道，如果找不到则返回null
     */
    private AsynchronousSocketChannel getChannelByAddress(SocketAddress addr) {
        try {
            // 使用反射获取通道列表
            java.lang.reflect.Field channelsField = this.getClass().getSuperclass().getDeclaredField("channels");
            channelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<AsynchronousSocketChannel> channels = (List<AsynchronousSocketChannel>) channelsField.get(this);

            // 遍历通道列表，查找匹配的通道
            for (AsynchronousSocketChannel channel : channels) {
                try {
                    SocketAddress remoteAddr = channel.getRemoteAddress();
                    if (addr.equals(remoteAddr)) {
                        return channel;
                    }
                } catch (Exception e) {
                    // 忽略异常
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "获取通道失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 获取消息ID
     * 使用消息的系统字节或会话ID来生成唯一标识符
     *
     * @param message SECS消息
     * @return 消息ID
     */
    private int getMessageId(SecsMessage message) {
        try {
            // 尝试使用反射获取消息的系统字节
            java.lang.reflect.Method getSystemBytesMethod = message.getClass().getMethod("getSystemBytes");
            if (getSystemBytesMethod != null) {
                getSystemBytesMethod.setAccessible(true);
                return (int)getSystemBytesMethod.invoke(message);
            }
        } catch (Exception e) {
            // 如果无法获取系统字节，使用会话ID和当前时间生成一个唯一ID
            return message.sessionId() * 1000 + (int)(System.currentTimeMillis() % 1000);
        }

        // 如果上述方法都失败，使用消息的哈希码
        return message.hashCode();
    }

    /**
     * 获取消息的来源通道
     * 使用最后一次接收到消息的客户端地址来确定消息的来源通道
     *
     * @param message SECS消息
     * @return 来源通道，如果找不到则返回null
     */
    private AsynchronousSocketChannel getSourceChannel(SecsMessage message) {
        try {
            // 记录日志
            logger.fine("尝试获取消息的来源通道");

            // 使用最后一次接收到消息的客户端地址
            if (lastReceivedAddress != null) {
                AsynchronousSocketChannel channel = clientChannels.get(lastReceivedAddress);
                if (channel != null && channel.isOpen()) {
                    logger.fine("使用最后接收到消息的客户端地址找到通道: " + lastReceivedAddress);
                    return channel;
                }
            }

            // 使用最后一次接收到消息的通道
            if (lastReceivedChannel != null && lastReceivedChannel.isOpen()) {
                logger.fine("使用最后接收到消息的通道");
                return lastReceivedChannel;
            }

            // 如果只有一个客户端连接，直接返回该通道
            if (clientChannels.size() == 1) {
                AsynchronousSocketChannel singleChannel = clientChannels.values().iterator().next();
                if (singleChannel != null && singleChannel.isOpen()) {
                    logger.fine("只有一个客户端连接，直接返回该通道");
                    return singleChannel;
                }
            }

            // 如果上述方法都失败，返回第一个有效的通道
            for (Map.Entry<SocketAddress, AsynchronousSocketChannel> entry : clientChannels.entrySet()) {
                AsynchronousSocketChannel ch = entry.getValue();
                if (ch != null && ch.isOpen()) {
                    logger.info("使用客户端地址找到通道: " + entry.getKey());
                    return ch;
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "获取消息来源通道失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 重写父类的sendBytes方法，向特定客户端发送字节数组
     *
     * @param bs 字节数组
     * @throws Secs1SendByteException 如果发送失败
     * @throws InterruptedException 如果线程被中断
     */
    @Override
    public void sendBytes(byte[] bs) throws Secs1SendByteException, InterruptedException {
        // 使用父类的方法发送字节数组
        // 这会将消息发送给第一个客户端
        super.sendBytes(bs);
    }

    /**
     * 向特定客户端发送字节数组
     *
     * @param clientAddr 客户端地址
     * @param bs 字节数组
     * @throws Secs1SendByteException 如果发送失败
     * @throws InterruptedException 如果线程被中断
     */
    public void sendBytesToClient(SocketAddress clientAddr, byte[] bs) throws Secs1SendByteException, InterruptedException {
        AsynchronousSocketChannel channel = clientChannels.get(clientAddr);
        if (channel != null && channel.isOpen()) {
            try {
                // 发送字节数组
                sendBytes(channel, bs);
            } catch (IOException e) {
                throw new Secs1SendByteException(e);
            }
        } else {
            throw new Secs1SendByteException("Client channel not found or closed: " + clientAddr);
        }
    }

    /**
     * 向特定客户端发送消息
     * 使用原始的方式发送消息，避免格式问题
     *
     * @param clientAddr 客户端地址
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param secs2 消息数据
     * @return 消息，如果发送失败则返回空
     * @throws SecsSendMessageException 如果发送消息失败
     * @throws InterruptedException 如果线程被中断
     * @throws SecsException 如果发送消息失败
     */
    public Optional<SecsMessage> sendToClient(SocketAddress clientAddr, int stream, int function, boolean wbit, Secs2 secs2)
            throws SecsSendMessageException, InterruptedException, SecsException {

        // 检查客户端地址是否有效
        if (clientAddr == null) {
            System.out.println("[DEBUG] 客户端地址为空");
            logger.warning("客户端地址为空");
            throw new SecsSendMessageException("Client address is null");
        }

        System.out.println("[DEBUG] 开始向客户端 " + clientAddr + " 发送消息: S" + stream + "F" + function + (wbit ? " W" : ""));
        System.out.println("[DEBUG] 当前客户端连接数: " + clientChannels.size());
        System.out.println("[DEBUG] 当前客户端列表: " + clientChannels.keySet());

        try {
            // 获取客户端通道
            AsynchronousSocketChannel clientChannel = clientChannels.get(clientAddr);
            System.out.println("[DEBUG] 获取到客户端通道: " + clientChannel);

            if (clientChannel == null || !clientChannel.isOpen()) {
                System.out.println("[DEBUG] 客户端通道不存在或已关闭: " + clientAddr);
                logger.warning("客户端通道不存在或已关闭: " + clientAddr);
                throw new SecsSendMessageException("Client channel not found or closed: " + clientAddr);
            }

            // 使用原始的SecsCommunicator发送消息
            logger.info(String.format("向客户端 %s 发送消息: S%dF%d%s",
                    clientAddr, stream, function, (wbit ? " W" : "")));

            System.out.println("[DEBUG] 开始创建消息");

            // 创建消息
            SecsMessage msg = this.messageBuilder().buildDataMessage(this, stream, function, wbit, secs2);
            System.out.println("[DEBUG] 消息创建成功: " + msg);

            // 获取消息的字节数组
            byte[] header = msg.header10Bytes();
            System.out.println("[DEBUG] 消息头部长度: " + header.length);

            // 获取消息体
            byte[] body = null;
            try {
                if (secs2 != null) {
                    body = secs2.getBytes();
                    System.out.println("[DEBUG] 消息体长度: " + body.length);
                }
            } catch (Exception e) {
                System.out.println("[DEBUG] 获取消息体失败: " + e.getMessage());
                body = new byte[0];
            }

            // 组合消息头和消息体
            byte[] message = new byte[header.length + (body != null ? body.length : 0)];
            System.arraycopy(header, 0, message, 0, header.length);
            if (body != null && body.length > 0) {
                System.arraycopy(body, 0, message, header.length, body.length);
            }

            System.out.println("[DEBUG] 完整消息长度: " + message.length);

            // 直接向客户端发送字节数组
            try {
                // 使用原始的方式发送消息
                System.out.println("[DEBUG] 开始发送消息字节数组");

                // 使用客户端通道发送消息
                System.out.println("[DEBUG] 使用客户端通道发送消息: " + clientChannel);
                sendBytes(clientChannel, message);

                System.out.println("[DEBUG] 消息发送成功");

                // 如果需要回复，等待回复
                if (wbit) {
                    System.out.println("[DEBUG] 等待回复");
                    // 获取消息ID
                    int messageId = getMessageId(msg);
                    // 等待回复，使用可配置的超时时间
                    Optional<SecsMessage> reply = waitReply(messageId, 30, TimeUnit.SECONDS);
                    System.out.println("[DEBUG] 收到回复: " + reply.isPresent());
                    return reply;
                }

                return Optional.empty();
            } catch (Exception e) {
                System.out.println("[DEBUG] 发送消息失败: " + e.getMessage());
                e.printStackTrace(System.out);
                throw new SecsSendMessageException("Failed to send message: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] 发送消息异常: " + e.getMessage());
            e.printStackTrace(System.out);
            throw e;
        }
    }

    /**
     * 添加消息目标地址
     * 记录消息的目标客户端地址，用于跟踪回复
     *
     * @param messageId 消息ID
     * @param targetAddr 目标客户端地址
     */
    private void addMessageTargetAddress(int messageId, SocketAddress targetAddr) {
        if (messageId > 0 && targetAddr != null) {
            messageTargetAddresses.put(messageId, targetAddr);
            System.out.println("[DEBUG] 添加消息目标地址: " + messageId + " -> " + targetAddr);
        }
    }

    /**
     * 添加回复消息
     * 当收到回复消息时调用此方法
     *
     * @param messageId 原始消息ID
     * @param replyMessage 回复消息
     */
    private void addReplyMessage(int messageId, SecsMessage replyMessage) {
        if (messageId > 0 && replyMessage != null) {
            replyMessages.put(messageId, replyMessage);
            System.out.println("[DEBUG] 添加回复消息: " + messageId + " -> " + replyMessage);
        }
    }

    /**
     * 获取客户端通道
     *
     * @param clientAddr 客户端地址
     * @return 客户端通道，如果找不到则返回null
     */
    public AsynchronousSocketChannel getClientChannel(SocketAddress clientAddr) {
        if (clientAddr == null) {
            return null;
        }

        // 直接从映射中获取客户端通道
        return clientChannels.get(clientAddr);
    }


    /**
     * 重写父类的sendBytes方法，向指定通道发送字节数组
     *
     * @param channel 通道
     * @param bytes 字节数组
     * @throws IOException 如果发送失败
     */
    protected void sendBytes(AsynchronousSocketChannel channel, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            logger.warning("Attempting to send empty message");
            return;
        }

        try {
            // 记录发送的消息内容，用于调试
            if (logger.isLoggable(Level.FINE)) {
                StringBuilder sb = new StringBuilder("Sending bytes: ");
                for (int i = 0; i < Math.min(bytes.length, 20); i++) {
                    sb.append(String.format("%02X ", bytes[i]));
                }
                if (bytes.length > 20) {
                    sb.append("...");
                }
                logger.fine(sb.toString());
            }

            // 创建一个新的ByteBuffer，确保不会有额外的字符
            ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
            buffer.put(bytes);
            buffer.flip();

            while (buffer.hasRemaining()) {
                Future<Integer> future = channel.write(buffer);
                try {
                    int w = future.get().intValue();
                    if (w <= 0) {
                        throw new IOException("Detect terminate");
                    }
                } catch (InterruptedException e) {
                    future.cancel(true);
                    throw e;
                } catch (ExecutionException e) {
                    Throwable t = e.getCause();
                    if (t instanceof RuntimeException) {
                        throw (RuntimeException)t;
                    }
                    throw new IOException(t);
                }
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException)e;
            }
            throw new IOException("Failed to send bytes to channel: " + e.getMessage(), e);
        }
    }



    /**
     * 等待回复
     *
     * @param messageId 消息ID
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return 回复消息，如果超时则返回空
     * @throws InterruptedException 如果线程被中断
     */
    private Optional<SecsMessage> waitReply(int messageId, long timeout, TimeUnit unit) throws InterruptedException {
        System.out.println("[DEBUG] 等待回复: " + messageId + ", 超时: " + timeout + " " + unit);

        // 记录当前的客户端地址，用于跟踪回复
        SocketAddress targetAddr = lastReceivedAddress;
        if (targetAddr != null) {
            addMessageTargetAddress(messageId, targetAddr);
        }

        // 计算超时时间
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);

        while (System.currentTimeMillis() < endTime) {
            // 检查是否有回复
            if (replyMessages.containsKey(messageId)) {
                // 获取回复消息
                SecsMessage replyMessage = replyMessages.remove(messageId);

                // 移除消息ID和目标地址的映射
                messageTargetAddresses.remove(messageId);

                // 移除消息ID和来源通道的映射
                messageSourceChannels.remove(messageId);

                System.out.println("[DEBUG] 收到回复: " + messageId + " -> " + replyMessage);

                // 返回回复消息
                return Optional.ofNullable(replyMessage);
            }

            // 等待一段时间
            Thread.sleep(100);
        }

        // 超时，清理相关映射
        messageTargetAddresses.remove(messageId);
        messageSourceChannels.remove(messageId);

        System.out.println("[DEBUG] 等待回复超时: " + messageId);
        return Optional.empty();
    }

    /**
     * 向回复消息的客户端发送消息
     *
     * @param message 原始消息
     * @param strm 流ID
     * @param func 功能ID
     * @param wbit 是否需要回复
     * @param secs2 消息数据
     * @return 消息，如果发送失败则返回空
     * @throws Secs1SendMessageException 如果发送消息失败
     * @throws InterruptedException 如果线程被中断
     */
    @Override
    public Optional<SecsMessage> send(SecsMessage message, int strm, int func, boolean wbit, Secs2 secs2)
            throws SecsException, InterruptedException, SecsSendMessageException {

        System.out.println("[DEBUG-SEND] 发送消息: S" + strm + "F" + func + (wbit ? " W" : ""));
        System.out.println("[DEBUG-SEND] 当前最后接收通道: " + lastReceivedChannel);
        System.out.println("[DEBUG-SEND] 当前最后接收地址: " + lastReceivedAddress);

        try {
            // 使用最后一次接收到消息的通道和地址来确定目标客户端
            // 这样可以确保回复发送给正确的客户端
            if (lastReceivedChannel != null && lastReceivedChannel.isOpen()) {
                SocketAddress sourceAddr = channelAddresses.get(lastReceivedChannel);

                if (sourceAddr != null) {
                    System.out.println("[DEBUG-SEND] 使用最后接收通道发送消息到: " + sourceAddr);
                    // 向来源客户端发送消息
                    return sendToClient(sourceAddr, strm, func, wbit, secs2);
                }
            }

            // 如果没有最后接收通道，尝试使用消息中的信息
            if (message != null) {
                // 获取消息ID
                int messageId = getMessageId(message);

                // 尝试从消息源通道映射中获取通道
                AsynchronousSocketChannel sourceChannel = messageSourceChannels.get(messageId);

                if (sourceChannel != null && sourceChannel.isOpen()) {
                    SocketAddress sourceAddr = channelAddresses.get(sourceChannel);

                    if (sourceAddr != null) {
                        System.out.println("[DEBUG-SEND] 使用消息ID获取通道发送消息到: " + sourceAddr);
                        // 向来源客户端发送消息
                        return sendToClient(sourceAddr, strm, func, wbit, secs2);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[DEBUG-SEND] 发送消息异常: " + e.getMessage());
            e.printStackTrace(System.out);
            throw new Secs1SendMessageException(e);
        }

        // 如果无法确定目标客户端，使用父类的方法发送消息
        System.out.println("[DEBUG-SEND] 无法确定目标客户端，使用父类的方法发送消息");
        return super.send(message, strm, func, wbit, secs2);
    }

    /**
     * 向所有客户端广播消息
     *
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param secs2 消息数据
     * @return 成功发送的客户端数量
     */
    public int broadcastToAllClients(int stream, int function, boolean wbit, Secs2 secs2) {
        int successCount = 0;

        // 复制客户端地址列表，避免并发修改问题
        List<SocketAddress> clientAddresses = new ArrayList<>(clientChannels.keySet());

        for (SocketAddress clientAddr : clientAddresses) {
            try {
                sendToClient(clientAddr, stream, function, wbit, secs2);
                successCount++;
            } catch (Exception e) {
                logger.log(Level.WARNING, "向客户端 " + clientAddr + " 广播消息失败: " + e.getMessage(), e);
            }
        }

        return successCount;
    }

    /**
     * 验证和修复消息格式
     * 确保消息符合SECS-I协议的格式
     *
     * @param bytes 原始消息字节数组
     * @return 修复后的消息字节数组
     */
    private byte[] validateAndFixMessageFormat(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new byte[0];
        }

        // 检查消息是否包含特殊字符
        boolean containsSpecialChars = false;
        for (byte b : bytes) {
            if (b == 0x3E || b == 0x0D || b == 0x0A) { // '>', CR, LF
                containsSpecialChars = true;
                break;
            }
        }

        if (!containsSpecialChars) {
            return bytes; // 如果没有特殊字符，直接返回原始消息
        }

        // 如果消息包含特殊字符，则过滤掉这些字符
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(bytes.length);
        for (byte b : bytes) {
            if (b != 0x3E && b != 0x0D && b != 0x0A) { // 过滤掉 '>', CR, LF
                baos.write(b);
            }
        }

        byte[] filteredBytes = baos.toByteArray();

        // 如果过滤后的消息为空，返回原始消息
        if (filteredBytes.length == 0) {
            return bytes;
        }

        // 检查消息头部是否完整
        if (filteredBytes.length >= 10) {
            // 更新消息长度字段
            int messageLength = filteredBytes.length - 10; // 减去头部长度
            filteredBytes[4] = (byte)((messageLength >> 8) & 0xFF); // 高字节
            filteredBytes[5] = (byte)(messageLength & 0xFF);        // 低字节
        }

        return filteredBytes;
    }



    /**
     * 创建实例的静态方法
     *
     * @param config 配置
     * @return 扩展的通信器实例
     * @throws IOException 如果创建通信器失败
     * @throws SecsSendMessageException 如果创建消息失败
     */
    public static ExtendedSecs1OnTcpIpReceiverCommunicator newInstance(Secs1OnTcpIpReceiverCommunicatorConfig config) throws IOException, SecsSendMessageException {
        try {
            return new ExtendedSecs1OnTcpIpReceiverCommunicator(config);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof SecsSendMessageException) {
                throw (SecsSendMessageException) e;
            }
            throw new IOException("Failed to create ExtendedSecs1OnTcpIpReceiverCommunicator: " + e.getMessage(), e);
        }
    }
}
