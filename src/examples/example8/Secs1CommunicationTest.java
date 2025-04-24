package example8;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SECS-I通信测试类
 *
 * 这个类用于同时启动SECS-I服务器和客户端，测试它们之间的通信。
 */
public class Secs1CommunicationTest {

    private static final Logger logger = Logger.getLogger(Secs1CommunicationTest.class.getName());

    /**
     * 主方法，用于测试
     */
    public static void main(String[] args) {
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 启动服务器
            executor.submit(() -> {
                try {
                    logger.info("启动SECS-I服务器...");
                    Secs1TcpServerTest.main(args);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "服务器异常", e);
                }
            });

            // 等待服务器启动
            Thread.sleep(2000);

            // 启动客户端
            executor.submit(() -> {
                try {
                    logger.info("启动SECS-I客户端...");
                    Secs1TcpClientTest.main(args);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "客户端异常", e);
                }
            });

            // 等待测试完成
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.MINUTES);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "测试异常", e);
        } finally {
            // 确保线程池关闭
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
    }
}
