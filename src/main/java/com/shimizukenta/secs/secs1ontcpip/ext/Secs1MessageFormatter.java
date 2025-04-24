package com.shimizukenta.secs.secs1ontcpip.ext;

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SECS-I消息格式处理器
 * 用于确保消息格式符合SECS-I协议
 */
public class Secs1MessageFormatter {
    
    private static final Logger logger = Logger.getLogger(Secs1MessageFormatter.class.getName());
    
    /**
     * 构造函数
     */
    public Secs1MessageFormatter() {
        // 空构造函数
    }
    
    /**
     * 格式化SECS-I消息
     * 确保消息格式符合SECS-I协议
     * 
     * @param bytes 原始消息字节数组
     * @return 格式化后的消息字节数组
     */
    public byte[] formatMessage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new byte[0];
        }
        
        // 检查消息长度是否至少包含头部
        if (bytes.length < 10) {
            logger.warning("消息长度不足: " + bytes.length);
            return bytes;
        }
        
        // 创建一个新的字节数组，确保不会修改原始数组
        byte[] formattedBytes = new byte[bytes.length];
        System.arraycopy(bytes, 0, formattedBytes, 0, bytes.length);
        
        // 更新消息长度字段
        int messageLength = bytes.length - 10; // 减去头部长度
        formattedBytes[4] = (byte)((messageLength >> 8) & 0xFF); // 高字节
        formattedBytes[5] = (byte)(messageLength & 0xFF);        // 低字节
        
        // 检查是否包含无效字符
        boolean containsInvalidChars = false;
        for (byte b : formattedBytes) {
            if (b == 0x3E || b == 0x0D || b == 0x0A || b == 0x22 || b == 0x45 || b == 0x04 || b == 0x15) {
                containsInvalidChars = true;
                break;
            }
        }
        
        if (containsInvalidChars) {
            logger.warning("消息包含无效字符，将进行过滤");
            return filterInvalidChars(formattedBytes);
        }
        
        return formattedBytes;
    }
    
    /**
     * 过滤无效字符
     * 
     * @param bytes 原始字节数组
     * @return 过滤后的字节数组
     */
    private byte[] filterInvalidChars(byte[] bytes) {
        // 创建一个新的ByteBuffer，用于存储过滤后的字节
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
        
        // 保留头部10个字节
        buffer.put(bytes, 0, 10);
        
        // 过滤消息体中的无效字符
        for (int i = 10; i < bytes.length; i++) {
            byte b = bytes[i];
            // 过滤掉特定的无效字符
            if (b != 0x3E && b != 0x0D && b != 0x0A && b != 0x22 && b != 0x45 && b != 0x04 && b != 0x15) {
                buffer.put(b);
            }
        }
        
        // 获取过滤后的字节数组
        buffer.flip();
        byte[] filteredBytes = new byte[buffer.remaining()];
        buffer.get(filteredBytes);
        
        // 更新消息长度字段
        int messageLength = filteredBytes.length - 10; // 减去头部长度
        if (messageLength >= 0) {
            filteredBytes[4] = (byte)((messageLength >> 8) & 0xFF); // 高字节
            filteredBytes[5] = (byte)(messageLength & 0xFF);        // 低字节
        }
        
        return filteredBytes;
    }
    
    /**
     * 验证消息格式
     * 
     * @param bytes 消息字节数组
     * @return 如果消息格式有效返回true，否则返回false
     */
    public boolean isValidFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 10) {
            return false;
        }
        
        // 检查消息长度字段
        int expectedLength = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
        int actualLength = bytes.length - 10;
        
        return expectedLength == actualLength;
    }
    
    /**
     * 创建SECS-I消息头部
     * 
     * @param deviceId 设备ID
     * @param stream 流ID
     * @param function 功能ID
     * @param wbit 是否需要回复
     * @param bodyLength 消息体长度
     * @param systemBytes 系统字节
     * @return 消息头部字节数组
     */
    public byte[] createMessageHeader(int deviceId, int stream, int function, boolean wbit, int bodyLength, byte[] systemBytes) {
        byte[] header = new byte[10];
        
        // 设备ID
        header[0] = (byte)((deviceId >> 8) & 0x7F); // 高字节
        header[1] = (byte)(deviceId & 0xFF);        // 低字节
        
        // 流ID和功能ID
        header[2] = (byte)(stream & 0x7F);
        if (wbit) {
            header[2] |= 0x80; // 设置W位
        }
        header[3] = (byte)(function & 0xFF);
        
        // 消息体长度
        header[4] = (byte)((bodyLength >> 8) & 0xFF); // 高字节
        header[5] = (byte)(bodyLength & 0xFF);        // 低字节
        
        // 系统字节
        if (systemBytes != null && systemBytes.length >= 4) {
            System.arraycopy(systemBytes, 0, header, 6, 4);
        }
        
        return header;
    }
}
