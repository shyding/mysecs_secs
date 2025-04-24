package com.shimizukenta.secs.secs1ontcpip.ext;

import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;

import com.shimizukenta.secs.SecsMessage;

/**
 * 消息上下文对象，用于在整个消息处理过程中传递通道信息
 * 仅用于底层实现，不暴露给外部
 */
class MessageContext {
    private final SecsMessage message;
    private final AsynchronousSocketChannel channel;
    private final SocketAddress sourceAddr;
    
    /**
     * 构造函数
     * 
     * @param message 消息对象
     * @param channel 通道
     * @param sourceAddr 源地址
     */
    public MessageContext(SecsMessage message, AsynchronousSocketChannel channel, SocketAddress sourceAddr) {
        this.message = message;
        this.channel = channel;
        this.sourceAddr = sourceAddr;
    }
    
    /**
     * 获取消息对象
     * 
     * @return 消息对象
     */
    public SecsMessage getMessage() {
        return message;
    }
    
    /**
     * 获取通道
     * 
     * @return 通道
     */
    public AsynchronousSocketChannel getChannel() {
        return channel;
    }
    
    /**
     * 获取源地址
     * 
     * @return 源地址
     */
    public SocketAddress getSourceAddress() {
        return sourceAddr;
    }
}
