package example7;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsLog;
import com.shimizukenta.secs.SecsLogListener;
import com.shimizukenta.secs.SecsMessage;

import com.shimizukenta.secs.hsms.HsmsMessage;
import com.shimizukenta.secs.hsms.HsmsMessageType;

import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.secs1.Secs1Message;
import com.shimizukenta.secs.secs1.Secs1TooBigSendMessageException;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpCommunicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * 优化版协议转换器
 *
 * 这个类实现了SECS-I和HSMS-SS之间的协议转换，并针对T7超时问题进行了优化。
 * 主要优化点包括：
 * 1. 增加T7超时时间
 * 2. 实现健壮的重连机制
 * 3. 添加消息缓冲
 * 4. 增强日志和监控
 * 5. 添加主动连接检测
 */
public class ProtocolConverter2Optimized implements Closeable {

    private static final Logger logger = Logger.getLogger(ProtocolConverter2Optimized.class.getName());

    public static final byte REJECT_BY_OVERFLOW = (byte)0x80;
    public static final byte REJECT_BY_NOT_CONNECT = (byte)0x81;

    private final Secs1OnTcpIpCommunicator secs1Comm;
    private final HsmsSsCommunicator hsmsSsComm;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean opened;
    private boolean closed;

    // 重连相关参数
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long RECONNECT_DELAY_MS = 5000; // 5秒重连延迟

    // 消息缓冲
    private final ConcurrentLinkedQueue<MessageWrapper> messageBuffer = new ConcurrentLinkedQueue<>();
    private static final int MAX_BUFFER_SIZE = 100;
    private static final int MAX_RETRY_COUNT = 3;

    // 性能监控计数器
    private final AtomicLong secs1ToHsmsMessageCount = new AtomicLong(0);
    private final AtomicLong hsmsToSecs1MessageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    /**
     * 消息包装类，用于消息缓冲
     */
    private class MessageWrapper {
        final SecsMessage message;
        final boolean isFromSecs1;
        int retryCount;

        MessageWrapper(SecsMessage message, boolean isFromSecs1) {
            this.message = message;
            this.isFromSecs1 = isFromSecs1;
            this.retryCount = 0;
        }
    }

    /**
     * 构造函数
     *
     * @param secs1Config SECS-I配置
     * @param hsmsConfig HSMS-SS配置
     */
    public ProtocolConverter2Optimized(
            Secs1OnTcpIpCommunicatorConfig secs1Config,
            HsmsSsCommunicatorConfig hsmsConfig) {

        this.opened = false;
        this.closed = false;

        // 确保T7超时设置足够长
        if (hsmsConfig.timeout().t7().getTimeout() < 30.0F) {
            logger.warning("T7超时设置过短，已自动调整为30秒");
            hsmsConfig.timeout().t7(30.0F);
        }

        // 创建通信器
        this.secs1Comm = Secs1OnTcpIpCommunicator.newInstance(secs1Config);
        this.hsmsSsComm = HsmsSsCommunicator.newInstance(hsmsConfig);

        // 添加SECS-I消息接收监听器
        this.secs1Comm.addSecsMessageReceiveListener(msg -> {
            try {
                logger.fine("收到SECS-I消息: S" + msg.getStream() + "F" + msg.getFunction() +
                        (msg.wbit() ? " W" : "") + " " + msg.secs2());

                if (!this.hsmsSsComm.isCommunicatable()) {
                    // 如果HSMS-SS不可通信，缓存消息
                    if (messageBuffer.size() < MAX_BUFFER_SIZE) {
                        messageBuffer.offer(new MessageWrapper(msg, true));
                        logger.info("HSMS-SS不可通信，消息已缓存，当前缓存大小: " + messageBuffer.size());
                    } else {
                        logger.warning("消息缓冲区已满，丢弃消息");
                    }
                    return;
                }

                try {
                    this.hsmsSsComm.send(this.toHsmsMessageFromSecs1Message(msg))
                    .filter(r -> r.isDataMessage())
                    .ifPresent(r -> {
                        try {
                            try {
                                this.secs1Comm.send(this.toSecs1MessageFromHsmsMessage(r));
                                hsmsToSecs1MessageCount.incrementAndGet();
                            } catch (SecsException nothing) {
                                logger.log(Level.WARNING, "发送SECS-I消息失败", nothing);
                            }
                        } catch (InterruptedException ignore) {
                            Thread.currentThread().interrupt();
                        }
                    });

                    secs1ToHsmsMessageCount.incrementAndGet();
                } catch (SecsException e) {
                    logger.log(Level.WARNING, "发送HSMS-SS消息失败", e);
                    errorCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 添加HSMS-SS消息接收监听器
        this.hsmsSsComm.addSecsMessageReceiveListener(msg -> {
            try {
                logger.fine("收到HSMS-SS消息: S" + msg.getStream() + "F" + msg.getFunction() +
                        (msg.wbit() ? " W" : "") + " " + msg.secs2());

                if (!this.secs1Comm.isCommunicatable()) {
                    // 如果SECS-I不可通信，缓存消息
                    if (messageBuffer.size() < MAX_BUFFER_SIZE) {
                        messageBuffer.offer(new MessageWrapper(msg, false));
                        logger.info("SECS-I不可通信，消息已缓存，当前缓存大小: " + messageBuffer.size());
                    } else {
                        logger.warning("消息缓冲区已满，丢弃消息");
                    }
                    return;
                }

                try {
                    this.secs1Comm.send(this.toSecs1MessageFromHsmsMessage(msg))
                    .ifPresent(r -> {
                        try {
                            try {
                                this.hsmsSsComm.send(this.toHsmsMessageFromSecs1Message(r));
                                secs1ToHsmsMessageCount.incrementAndGet();
                            } catch (SecsException nothing) {
                                logger.log(Level.WARNING, "发送HSMS-SS消息失败", nothing);
                            }
                        } catch (InterruptedException ignore) {
                            Thread.currentThread().interrupt();
                        }
                    });

                    hsmsToSecs1MessageCount.incrementAndGet();
                } catch (Secs1TooBigSendMessageException e) {
                    logger.log(Level.WARNING, "SECS-I消息过大", e);
                    errorCount.incrementAndGet();

                    try {
                        this.hsmsSsComm.send(this.toHsmsRejectMessage(msg, REJECT_BY_OVERFLOW));
                    } catch (SecsException giveup) {
                        logger.log(Level.WARNING, "发送拒绝消息失败", giveup);
                    }
                } catch (SecsException e) {
                    logger.log(Level.WARNING, "发送SECS-I消息失败", e);
                    errorCount.incrementAndGet();

                    try {
                        this.hsmsSsComm.send(this.toHsmsRejectMessage(msg, REJECT_BY_NOT_CONNECT));
                    } catch (SecsException giveup) {
                        logger.log(Level.WARNING, "发送拒绝消息失败", giveup);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 添加HSMS-SS连接状态监听器
        this.hsmsSsComm.addSecsCommunicatableStateChangeListener(state -> {
            logger.info("HSMS-SS连接状态变更为: " + state);
            if (!state && running.get()) {
                handleHsmsDisconnection();
            }
        });

        // 添加通信状态监听器
        this.hsmsSsComm.addSecsCommunicatableStateChangeListener(communicatable -> {
            logger.info("HSMS-SS通信状态变更为: " + (communicatable ? "可通信" : "不可通信"));
        });

        this.secs1Comm.addSecsCommunicatableStateChangeListener(communicatable -> {
            logger.info("SECS-I通信状态变更为: " + (communicatable ? "可通信" : "不可通信"));
        });
    }

    /**
     * 处理HSMS-SS断开连接
     */
    private void handleHsmsDisconnection() {
        int count = reconnectCount.incrementAndGet();
        if (count <= MAX_RECONNECT_ATTEMPTS) {
            logger.info("尝试重新连接HSMS-SS，第" + count + "次尝试");
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
                if (this.hsmsSsComm.isClosed()) {
                    this.hsmsSsComm.open();
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "HSMS-SS重连失败", e);
            }
        } else {
            logger.warning("HSMS-SS重连次数超过最大限制(" + MAX_RECONNECT_ATTEMPTS + ")，停止重连");
        }
    }

    /**
     * 处理缓冲消息
     */
    private void processBufferedMessages() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(5000); // 每5秒检查一次

                    if (messageBuffer.isEmpty()) {
                        continue;
                    }

                    // 检查通信状态
                    boolean hsmsReady = this.hsmsSsComm.isCommunicatable();
                    boolean secs1Ready = this.secs1Comm.isCommunicatable();

                    if (!hsmsReady && !secs1Ready) {
                        continue; // 两边都不可通信，等待下一次检查
                    }

                    // 处理缓冲区中的消息
                    Iterator<MessageWrapper> iterator = messageBuffer.iterator();
                    while (iterator.hasNext()) {
                        MessageWrapper wrapper = iterator.next();

                        if (wrapper.isFromSecs1 && hsmsReady) {
                            // 处理从SECS-I到HSMS-SS的缓存消息
                            try {
                                SecsMessage msg = wrapper.message;

                                this.hsmsSsComm.send(this.toHsmsMessageFromSecs1Message(msg));
                                secs1ToHsmsMessageCount.incrementAndGet();
                                logger.info("已处理缓存的SECS-I消息");

                                iterator.remove();
                            } catch (Exception e) {
                                wrapper.retryCount++;
                                if (wrapper.retryCount >= MAX_RETRY_COUNT) {
                                    logger.log(Level.WARNING, "缓存消息重试次数超过限制，丢弃消息", e);
                                    iterator.remove();
                                }
                            }
                        } else if (!wrapper.isFromSecs1 && secs1Ready) {
                            // 处理从HSMS-SS到SECS-I的缓存消息
                            try {
                                SecsMessage msg = wrapper.message;

                                this.secs1Comm.send(this.toSecs1MessageFromHsmsMessage(msg));
                                hsmsToSecs1MessageCount.incrementAndGet();
                                logger.info("已处理缓存的HSMS-SS消息");

                                iterator.remove();
                            } catch (Exception e) {
                                wrapper.retryCount++;
                                if (wrapper.retryCount >= MAX_RETRY_COUNT) {
                                    logger.log(Level.WARNING, "缓存消息重试次数超过限制，丢弃消息", e);
                                    iterator.remove();
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "处理缓冲消息时发生错误", e);
                }
            }
        }).start();
    }

    /**
     * 启动性能监控
     */
    private void startPerformanceMonitor() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(300000); // 每5分钟记录一次
                    logger.info("性能统计 - SECS-I到HSMS-SS: " + secs1ToHsmsMessageCount.getAndSet(0) +
                            "条消息, HSMS-SS到SECS-I: " + hsmsToSecs1MessageCount.getAndSet(0) +
                            "条消息, 错误: " + errorCount.getAndSet(0) + "次, 缓冲区大小: " + messageBuffer.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    /**
     * 添加主动连接检测
     */
    private void startActiveConnectionCheck() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(120000); // 每2分钟检查一次

                    if (this.hsmsSsComm.isOpen() && !this.hsmsSsComm.isCommunicatable()) {
                        // 如果HSMS-SS已打开但不可通信，尝试发送测试消息
                        try {
                            logger.info("发送S1F1测试消息检查HSMS-SS连接");
                            Optional<SecsMessage> reply = this.hsmsSsComm.send(1, 1, true, Secs2.empty());
                            if (reply.isPresent()) {
                                logger.info("收到S1F2回复，HSMS-SS连接正常");
                            } else {
                                logger.warning("未收到S1F2回复，HSMS-SS连接可能有问题");
                            }
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "发送测试消息失败，尝试重新连接HSMS-SS", e);
                            handleHsmsDisconnection();
                        }
                    }

                    // 检查SECS-I连接
                    if (this.secs1Comm.isOpen() && !this.secs1Comm.isCommunicatable()) {
                        logger.warning("SECS-I通信器已打开但不可通信");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "连接检查时发生错误", e);
                }
            }
        }).start();
    }

    /**
     * 将SECS-I消息转换为HSMS消息
     */
    private HsmsMessage toHsmsMessageFromSecs1Message(SecsMessage msg) {
        byte[] bs = msg.header10Bytes();

        byte[] header = new byte[] {
                (byte)((int)(bs[0]) & 0x7F),
                bs[1],
                bs[2],
                bs[3],
                (byte)0,
                (byte)0,
                bs[6],
                bs[7],
                bs[8],
                bs[9]
        };

        return HsmsMessage.of(header, msg.secs2());
    }

    /**
     * 将HSMS消息转换为SECS-I消息
     */
    private Secs1Message toSecs1MessageFromHsmsMessage(SecsMessage msg)
            throws Secs1TooBigSendMessageException {

        byte[] bs = msg.header10Bytes();

        byte[] header = new byte[] {
                bs[0],
                bs[1],
                bs[2],
                bs[3],
                (byte)0,
                (byte)0,
                bs[6],
                bs[7],
                bs[8],
                bs[9]
        };

        if (this.secs1Comm.isEquip()) {
            header[0] |= (byte)0x80;
        } else {
            header[0] &= (byte)0x7F;
        }

        return Secs1Message.of(header, msg.secs2());
    }

    /**
     * 创建HSMS拒绝消息
     */
    private HsmsMessage toHsmsRejectMessage(SecsMessage ref, byte reason) {
        byte[] bs = ref.header10Bytes();

        byte[] header = new byte[] {
                bs[0],
                bs[1],
                (byte)0x0,
                reason,
                HsmsMessageType.REJECT_REQ.pType(),
                HsmsMessageType.REJECT_REQ.sType(),
                bs[6],
                bs[7],
                bs[8],
                bs[9]
        };

        return HsmsMessage.of(header);
    }

    /**
     * 打开协议转换器
     */
    public void open() throws IOException {
        synchronized (this) {
            if (this.closed) {
                throw new IOException("Already closed");
            }
            if (this.opened) {
                throw new IOException("Already opened");
            }
            this.opened = true;
        }

        try {
            // 先打开HSMS-SS，再打开SECS-I
            logger.info("正在打开HSMS-SS通信器...");
            this.hsmsSsComm.open();

            logger.info("正在打开SECS-I通信器...");
            this.secs1Comm.open();

            // 启动后台任务
            this.start();

            logger.info("协议转换器已打开");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "打开协议转换器时发生错误", e);
            try {
                this.close();
            } catch (IOException ignore) {
            }
            throw e;
        }
    }

    /**
     * 启动协议转换器的后台任务
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            reconnectCount.set(0);

            // 启动后台任务
            processBufferedMessages();
            startPerformanceMonitor();
            startActiveConnectionCheck();

            logger.info("协议转换器后台任务已启动");
        } else {
            logger.warning("协议转换器后台任务已经在运行中");
        }
    }

    /**
     * 停止协议转换器的后台任务
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("协议转换器后台任务已停止");
        } else {
            logger.warning("协议转换器后台任务未在运行中");
        }
    }

    /**
     * 关闭协议转换器
     */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.closed = true;
        }

        // 停止后台任务
        this.stop();

        IOException ioExcept = null;

        try {
            logger.info("正在关闭SECS-I通信器...");
            this.secs1Comm.close();
        } catch (IOException e) {
            ioExcept = e;
            logger.log(Level.WARNING, "关闭SECS-I通信器时发生错误", e);
        }

        try {
            logger.info("正在关闭HSMS-SS通信器...");
            this.hsmsSsComm.close();
        } catch (IOException e) {
            ioExcept = e;
            logger.log(Level.WARNING, "关闭HSMS-SS通信器时发生错误", e);
        }

        logger.info("协议转换器已关闭");

        if (ioExcept != null) {
            throw ioExcept;
        }
    }

    /**
     * 检查协议转换器是否正在运行
     *
     * @return 如果正在运行则返回true
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 添加SECS日志监听器
     */
    public boolean addSecsLogListener(SecsLogListener<? super SecsLog> l) {
        boolean a = this.secs1Comm.addSecsLogListener(l);
        boolean b = this.hsmsSsComm.addSecsLogListener(l);
        return a && b;
    }

    /**
     * 移除SECS日志监听器
     */
    public boolean removeSecsLogListener(SecsLogListener<? super SecsLog> l) {
        boolean a = this.secs1Comm.removeSecsLogListener(l);
        boolean b = this.hsmsSsComm.removeSecsLogListener(l);
        return a || b;
    }

    /**
     * 创建协议转换器实例
     */
    public static ProtocolConverter2Optimized newInstance(
            Secs1OnTcpIpCommunicatorConfig secs1Config,
            HsmsSsCommunicatorConfig hsmsConfig) {

        return new ProtocolConverter2Optimized(secs1Config, hsmsConfig);
    }
}
