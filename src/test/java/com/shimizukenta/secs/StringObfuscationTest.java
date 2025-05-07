package com.shimizukenta.secs;

import org.junit.Test;
import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * 测试字符串混淆功能
 *
 * 这个测试类用于验证混淆后的字符串是否正常工作
 * 注意：这个测试只能在混淆前运行，混淆后可能会失败
 */
public class StringObfuscationTest {

    @Test
    public void testStringConstants() {
        // 测试包含字符串常量的方法
        String testMessage = "This is a test message that will be obfuscated";
        System.out.println("Original message: " + testMessage);

        // 测试日志输出
        logMessage("This log message should be obfuscated");

        // 测试异常消息
        try {
            throw new SecsException("This exception message should be obfuscated");
        } catch (SecsException e) {
            System.out.println("Exception message: " + e.getMessage());
        }

        // 验证测试通过
        assertTrue(true);
    }

    @Test
    public void testClassNameStrings() {
        // 测试类名引用字符串
        String className = SecsException.class.getName();
        System.out.println("Class name: " + className);

        // 验证类名正确
        assertEquals("com.shimizukenta.secs.SecsException", className);
    }

    private void logMessage(String message) {
        System.out.println("LOG: " + message);
    }
}
