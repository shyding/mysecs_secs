package com.shimizukenta.secs.impl;

import java.net.SocketAddress;

/**
 * This abstract class extends AbstractSecsMessage to add source address information.
 * 
 * @author augment-agent
 *
 */
public abstract class AbstractSecsMessageWithSource extends AbstractSecsMessage {
    
    private static final long serialVersionUID = 1L;
    
    private SocketAddress sourceAddr;
    
// 定义一个受保护的构造函数，用于创建AbstractSecsMessageWithSource类的实例
    protected AbstractSecsMessageWithSource() {
    // 调用父类的构造函数，确保父类的初始化逻辑被执行
        super();
    // 初始化当前类的sourceAddr属性为null，表示消息的源地址尚未设置
        this.sourceAddr = null;
    }
    
    /**
     * Get source address of this message.
     * 
     * @return source address, null if not set
     */
    public SocketAddress getSourceAddress() {
        return this.sourceAddr;
    }
    
    /**
     * Set source address of this message.
     * 
     * @param addr source addre ss
     */
    public void setSourceAddress(SocketAddress addr) {
        this.sourceAddr = addr;
    }
    
}
