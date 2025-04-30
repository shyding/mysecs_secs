package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import java.io.PrintWriter;
import java.io.StringWriter;

public class ExceptionUtil {

    /**
     * 将 Throwable 异常堆栈信息转为字符串
     *
     * @param throwable 异常对象
     * @return 堆栈信息字符串
     */
    public static String getStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "Throwable is null";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }
}
