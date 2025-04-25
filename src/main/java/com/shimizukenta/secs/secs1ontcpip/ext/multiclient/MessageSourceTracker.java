package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.SecsMessage;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 消息来源跟踪器
 */
public class MessageSourceTracker {
    
    private static final Logger logger = Logger.getLogger(MessageSourceTracker.class.getName());
    
    private final Map<SecsMessage, SocketAddress> messageSourceMap = new ConcurrentHashMap<>();
    private final Map<Long, SocketAddress> receiveTimeAddressMap = new ConcurrentHashMap<>();
    private final long TIME_WINDOW = 1000; // 1秒时间窗口
    
    /**
     * 构造函数
     */
    public MessageSourceTracker() {
        // 空构造函数
    }
    
    /**
     * 记录消息来源
     * 
     * @param message 消息
     * @param sourceAddress 来源地址
     */
    public void trackMessageSource(SecsMessage message, SocketAddress sourceAddress) {
        if (message != null && sourceAddress != null) {
            logger.fine(String.format("记录消息来源: S%dF%d%s, 来源: %s", 
                    message.getStream(), message.getFunction(), 
                    (message.wbit() ? " W" : ""), sourceAddress));
            
            messageSourceMap.put(message, sourceAddress);
        }
    }
    
    /**
     * 记录接收时间和地址
     * 
     * @param time 接收时间
     * @param sourceAddress 来源地址
     */
    public void trackReceiveTime(long time, SocketAddress sourceAddress) {
        if (sourceAddress != null) {
            receiveTimeAddressMap.put(time, sourceAddress);
            
            // 清理旧记录
            cleanupOldMappings(time - TIME_WINDOW);
            
            logger.fine("记录接收时间与客户端地址的关联: " + time + " -> " + sourceAddress);
        }
    }
    
    /**
     * 清理旧的映射
     * 
     * @param oldestTime 最早时间
     */
    private void cleanupOldMappings(long oldestTime) {
        receiveTimeAddressMap.entrySet().removeIf(entry -> entry.getKey() < oldestTime);
    }
    
    /**
     * 获取消息来源
     * 
     * @param message 消息
     * @return 来源地址，如果未知则返回null
     */
    public SocketAddress getMessageSource(SecsMessage message) {
        if (message != null) {
            // 首先尝试从直接映射中获取
            SocketAddress addr = messageSourceMap.get(message);
            
            if (addr != null) {
                logger.fine(String.format("从直接映射获取消息来源: S%dF%d%s, 来源: %s", 
                        message.getStream(), message.getFunction(), 
                        (message.wbit() ? " W" : ""), addr));
                return addr;
            }
            
            // 如果直接映射中没有，尝试从时间映射中获取
            addr = findMostRecentAddress();
            
            if (addr != null) {
                logger.fine(String.format("从时间映射获取消息来源: S%dF%d%s, 来源: %s", 
                        message.getStream(), message.getFunction(), 
                        (message.wbit() ? " W" : ""), addr));
                
                // 更新直接映射
                trackMessageSource(message, addr);
                
                return addr;
            }
            
            logger.fine(String.format("无法确定消息来源: S%dF%d%s", 
                    message.getStream(), message.getFunction(), 
                    (message.wbit() ? " W" : "")));
        }
        
        return null;
    }
    
    /**
     * 查找最近的地址
     * 
     * @return 最近的地址，如果没有则返回null
     */
    private SocketAddress findMostRecentAddress() {
        long currentTime = System.currentTimeMillis();
        long mostRecentTime = 0;
        SocketAddress mostRecentAddr = null;
        
        for (Map.Entry<Long, SocketAddress> entry : receiveTimeAddressMap.entrySet()) {
            long time = entry.getKey();
            if (time <= currentTime && time > mostRecentTime) {
                mostRecentTime = time;
                mostRecentAddr = entry.getValue();
            }
        }
        
        return mostRecentAddr;
    }
    
    /**
     * 清除消息来源
     * 
     * @param message 消息
     */
    public void clearMessageSource(SecsMessage message) {
        if (message != null) {
            SocketAddress addr = messageSourceMap.remove(message);
            
            if (addr != null) {
                logger.fine(String.format("清除消息来源: S%dF%d%s, 来源: %s", 
                        message.getStream(), message.getFunction(), 
                        (message.wbit() ? " W" : ""), addr));
            }
        }
    }
    
    /**
     * 清除客户端的所有消息
     * 
     * @param sourceAddress 客户端地址
     */
    public void clearClientMessages(SocketAddress sourceAddress) {
        if (sourceAddress != null) {
            int count = 0;
            
            for (Map.Entry<SecsMessage, SocketAddress> entry : messageSourceMap.entrySet()) {
                if (sourceAddress.equals(entry.getValue())) {
                    messageSourceMap.remove(entry.getKey());
                    count++;
                }
            }
            
            // 清除时间映射
            receiveTimeAddressMap.entrySet().removeIf(entry -> sourceAddress.equals(entry.getValue()));
            
            logger.fine("清除客户端 " + sourceAddress + " 的所有消息，共 " + count + " 条");
        }
    }
}
