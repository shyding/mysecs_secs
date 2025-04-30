package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs1.Secs1Message;
import com.shimizukenta.secs.secs1.Secs1MessageBlock;
import com.shimizukenta.secs.secs1.Secs1SendByteException;
import com.shimizukenta.secs.secs1.Secs1SendMessageException;
import com.shimizukenta.secs.secs1.impl.AbstractSecs1Communicator;
import com.shimizukenta.secs.secs1ontcpip.*;
import com.shimizukenta.secs.secs1ontcpip.impl.AbstractSecs1OnTcpIpLogObserverFacade;
import com.shimizukenta.secs.secs1ontcpip.impl.AbstractSecs1OnTcpIpReceiverCommunicator;
import com.shimizukenta.secs.secs1ontcpip.impl.Secs1OnTcpIpLogObservableImpl;
import com.shimizukenta.secs.secs2.Secs2;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 抽象SECS-I TCP/IP多客户端通信器
 *
 * 这个类扩展了标准的AbstractSecs1Communicator，
 * 添加了对多客户端连接的支持
 */
public abstract class AbstractSecs1OnTcpIpMultiClientCommunicator extends AbstractSecs1Communicator
        implements Secs1OnTcpIpMultiClientCommunicator, Secs1OnTcpIpLogObservableImpl {

    private static final Logger logger = Logger.getLogger(AbstractSecs1OnTcpIpMultiClientCommunicator.class.getName());

    private final Secs1OnTcpIpReceiverCommunicatorConfig config;
    private final AsynchronousSocketChannel channel;
    private final SocketAddress remoteSocketAddress;
    private final AbstractSecs1OnTcpIpLogObserverFacade secs1OnTcpIpLogObserver;



    /**
     * 构造函数
     *
     * @param config 通信器配置
     * @param channel 客户端通道
     * @throws IOException 如果创建通信器失败
     */
    public AbstractSecs1OnTcpIpMultiClientCommunicator(
            Secs1OnTcpIpReceiverCommunicatorConfig config,
            AsynchronousSocketChannel channel) throws IOException {

        super(Objects.requireNonNull(config));

        this.config = config;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.secs1OnTcpIpLogObserver = new AbstractSecs1OnTcpIpLogObserverFacade(config, this.executorService()) {};

        try {
            this.remoteSocketAddress = channel.getRemoteAddress();

        } catch (IOException e) {
            throw new IOException("Failed to get remote address", e);
        }


    }


    // 用于同步读取操作的锁对象
    private final Object readLock = new Object();

    protected void reading(AsynchronousSocketChannel channel) throws Exception {
        // 使用同步锁确保同一时间只有一个读取操作
        synchronized (readLock) {
            try {
                final ByteBuffer buffer = ByteBuffer.allocate(1024);
                SocketAddress remoteAddress = null;

                try {
                    remoteAddress = channel.getRemoteAddress();
                    logger.info("Started reading from channel: " + remoteAddress);
                } catch (IOException e) {
                    logger.warning("Failed to get remote address: " + e.getMessage());
                }

                while (channel.isOpen()) {
                    ((Buffer)buffer).clear();

                    // 创建读取操作
                    logger.fine("Initiating read operation on channel: " + remoteAddress);
                    final Future<Integer> f = channel.read(buffer);

                    try {
                        // 等待读取完成
                        logger.fine("Waiting for read completion on channel: " + remoteAddress);
                        int r = f.get().intValue();
                        logger.fine("Read completed on channel: " + remoteAddress + ", bytes read: " + r);

                        if (r < 0) {
                            // 通道已关闭或到达流末尾
                            logger.info("Channel closed or end of stream reached: " + remoteAddress);
                            break;
                        }

                        ((Buffer)buffer).flip();

                        byte[] bs = new byte[buffer.remaining()];
                        buffer.get(bs);

                        // 输出接收到的数据的十六进制表示
                        StringBuilder hexData = new StringBuilder();
                        for (byte b : bs) {
                            hexData.append(String.format("%02X ", b));
                            if (hexData.length() > 100) {
                                hexData.append("..."); // 限制输出长度
                                break;
                            }
                        }
                        logger.info("Received data from " + remoteAddress + ": " + hexData.toString());

                        // 处理接收到的数据
                        logger.info("Processing received data, length: " + bs.length);
                        try {
                            putBytes(bs);
                            logger.info("Data processing completed");
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Error processing data: " + e.getMessage(), e);
                        }
                    }
                    catch (InterruptedException e) {
                        // 取消读取操作并重新抛出异常
                        logger.warning("Read operation interrupted: " + e.getMessage());
                        f.cancel(true);
                        throw e;
                    }
                    catch (ExecutionException e) {
                        // 处理读取操作中的异常
                        Throwable t = e.getCause();
                        logger.warning("Read operation failed: " + t.getMessage());

                        if (t instanceof ClosedChannelException) {
                            // 通道已关闭，退出循环
                            logger.info("Channel is closed, exiting read loop: " + remoteAddress);
                            break;
                        } else if (t instanceof Exception) {
                            throw (Exception)t;
                        } else {
                            throw new Exception("Unknown error during read operation", t);
                        }
                    }
                }

                logger.info("Exited read loop for channel: " + remoteAddress);
            }
            catch (InterruptedException ignore) {
                // 线程被中断，退出读取循环
                logger.info("Reading thread interrupted, exiting read method");
            }
            catch (Exception e) {
                logger.log(Level.SEVERE, "Unexpected error in reading method", e);
                throw e;
            }
        }
    }


    protected AsynchronousSocketChannel getChannel() throws Secs1OnTcpIpNotConnectedException {
        synchronized ( this.channel ) {
            if ( !this.channel.isOpen() ) {
                throw new Secs1OnTcpIpNotConnectedException();
            }
            return this.channel;
        }
    }

    @Override
    public void sendBytes(byte[] bs)
            throws Secs1SendByteException, InterruptedException {

        final AsynchronousSocketChannel channel = getChannel();

        final ByteBuffer buffer = ByteBuffer.allocate(bs.length);
        buffer.put(bs);
        ((Buffer)buffer).flip();

        while ( buffer.hasRemaining() ) {

            final Future<Integer> f = channel.write(buffer);

            try {
                int w = f.get().intValue();

                if ( w <= 0 ) {
                    throw new Secs1OnTcpIpDetectTerminateException();
                }
            }
            catch ( InterruptedException e ) {
                f.cancel(true);
                throw e;
            }
            catch ( ExecutionException e ) {

                Throwable t = e.getCause();

                if ( t instanceof RuntimeException ) {
                    throw (RuntimeException)t;
                }

                throw new Secs1SendByteException(t);
            }
        }
    }
    /**
     * 获取远程地址
     *
     * @return 远程地址
     */
    public SocketAddress getRemoteSocketAddress() {
        return remoteSocketAddress;
    }


    @Override
    public AbstractSecs1OnTcpIpLogObserverFacade secs1OnTcpIpLogObserver() {
        return this.secs1OnTcpIpLogObserver;
    }

    // 读取线程的引用，用于跟踪和管理
    private volatile Thread readingThread = null;

    // 通信器状态
    private final AtomicBoolean opened = new AtomicBoolean(false);

    /**
     * 打开通信器
     *
     * @throws IOException 如果打开通信器失败
     */
    @Override
    public void open() throws IOException {
        // 如果已经打开，直接返回
        if (opened.get()) {
            logger.info("Communicator already opened");
            return;
        }

        // 启动消息处理线程
        this.executorService().execute(super.circuit);

        try {
            final SocketAddress pLocal = channel.getLocalAddress();
            final SocketAddress pRemote = channel.getRemoteAddress();

            // 记录连接已建立
            this.secs1OnTcpIpLogObserver().offerSecs1OnTcpIpChannelConnectionAccepted(pLocal, pRemote);

            // 确保不会启动多个读取线程
            synchronized (readLock) {
                // 如果已经有读取线程在运行，则不再启动新的读取线程
                if (readingThread != null && readingThread.isAlive()) {
                    logger.info("Reading thread already running for channel: " + pRemote);
                    // 更新通信状态
                    this.secsCommunicateStateObserver().setSecsCommunicateState(true);
                    opened.set(true);
                    return;
                }

                // 创建并启动新的读取线程
                Thread thread = new Thread(() -> {
                    try {
                        // 直接调用reading方法，它内部已经有同步锁
                        reading(channel);
                    }
                    catch (Exception e) {
                        if (!(e instanceof InterruptedException) && !(e.getCause() instanceof ClosedChannelException)) {
                            this.offerThrowableToLog(e);
                        }
                    }
                    finally {
                        try {
                            if (channel.isOpen()) {
                                try {
                                    channel.shutdownOutput();
                                }
                                catch (IOException ignore) {
                                }

                                try {
                                    channel.close();
                                }
                                catch (IOException ignore) {
                                }

                                this.secs1OnTcpIpLogObserver().offerSecs1OnTcpIpChannelConnectionAcceptClosed(pLocal, pRemote);
                            }
                        }
                        catch (Exception ignore) {
                        }

                        // 清除读取线程引用
                        synchronized (readLock) {
                            if (readingThread == Thread.currentThread()) {
                                readingThread = null;
                            }
                        }

                        // 更新通信状态
                        this.secsCommunicateStateObserver().setSecsCommunicateState(false);
                        opened.set(false);
                    }
                }, "ReadingThread-" + pRemote);

                // 设置为后台线程
                thread.setDaemon(true);

                // 保存线程引用
                readingThread = thread;

                // 启动线程
                thread.start();

                // 更新通信状态
                this.secsCommunicateStateObserver().setSecsCommunicateState(true);
                opened.set(true);

                logger.info("Started reading thread for channel: " + pRemote);
            }
        }
        catch (IOException e) {
            this.offerThrowableToLog(e);
            // 更新通信状态
            this.secsCommunicateStateObserver().setSecsCommunicateState(false);
            opened.set(false);
            throw e;
        }
    }


    /**
     * 关闭通信器
     *
     * @throws IOException 如果关闭通信器失败
     */
    @Override
    public void close() throws IOException {
        // 如果已经关闭，直接返回
        if (!opened.get()) {
            logger.info("Communicator already closed");
            return;
        }

        // 更新通信状态
        this.secsCommunicateStateObserver().setSecsCommunicateState(false);
        opened.set(false);

        // 调用父类的close方法
        super.close();

        try {
            // 中断读取线程
            Thread currentReadingThread = null;
            synchronized (readLock) {
                currentReadingThread = readingThread;
                if (currentReadingThread != null && currentReadingThread.isAlive()) {
                    logger.info("Interrupting reading thread: " + currentReadingThread.getName());
                    currentReadingThread.interrupt();
                }
            }

            // 获取地址信息（如果可用）
            SocketAddress remoteAddress = null;
            SocketAddress localAddress = null;

            try {
                remoteAddress = channel.getRemoteAddress();
                localAddress = channel.getLocalAddress();
            } catch (IOException ignore) {
                // 如果通道已经关闭，可能会抛出异常
            }

            // 关闭通道
            if (channel != null && channel.isOpen()) {
                try {
                    // 先关闭输出，再关闭通道
                    try {
                        channel.shutdownOutput();
                    } catch (IOException ignore) {
                        // 忽略关闭输出时的异常
                    }

                    // 关闭整个通道
                    channel.close();
                    logger.info("Channel closed: " + (remoteAddress != null ? remoteAddress.toString() : "unknown"));

                    // 记录关闭事件
                    if (localAddress != null && remoteAddress != null) {
                        this.secs1OnTcpIpLogObserver().offerSecs1OnTcpIpChannelConnectionAcceptClosed(localAddress, remoteAddress);
                    }
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to close channel: " + e.getMessage(), e);
                    throw e;
                }
            }

            // 等待读取线程结束（最多等待100ms）
            if (currentReadingThread != null && currentReadingThread.isAlive()) {
                try {
                    currentReadingThread.join(100);
                } catch (InterruptedException ignore) {
                    // 忽略中断
                }
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException)e;
            }
            throw new IOException("Failed to close channel", e);
        }
    }

    /**
     * 发送SECS消息并等待回复
     *
     * @param strm 消息流
     * @param func 功能码
     * @param secs2 SECS-2消息体
     * @return 回复消息
     * @throws SecsSendMessageException 如果发送失败
     * @throws SecsWaitReplyMessageException 如果等待回复超时
     * @throws SecsException 如果发生其他SECS异常
     * @throws InterruptedException 如果线程被中断
     */
    @Override
    public SecsMessage sendAndWaitReply(int strm, int func, Secs2 secs2)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {
        Optional<SecsMessage> opt = send(strm, func, true, secs2);
        if (opt.isPresent()) {
            return opt.get();
        } else {
            throw new SecsWaitReplyMessageException("Timeout waiting for reply");
        }
    }

    /**
     * 发送SECS消息并等待回复，带超时
     *
     * @param strm 消息流
     * @param func 功能码
     * @param secs2 SECS-2消息体
     * @param timeout 超时时间（毫秒）
     * @return 回复消息
     * @throws SecsSendMessageException 如果发送失败
     * @throws SecsWaitReplyMessageException 如果等待回复超时
     * @throws SecsException 如果发生其他SECS异常
     * @throws InterruptedException 如果线程被中断
     */
    @Override
    public SecsMessage sendAndWaitReply(int strm, int func, Secs2 secs2, long timeout)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException {
        // 在当前实现中，我们不支持自定义超时，所以直接调用标准方法
        return sendAndWaitReply(strm, func, secs2);
    }

    /**
     * 检查通信器是否打开
     *
     * @return 如果通信器已打开则返回true，否则返回false
     */
    @Override
    public boolean isOpen() {
        boolean isOpened = opened.get();
        boolean isChannelOpen = channel.isOpen();
        boolean result = isOpened && isChannelOpen;

        System.out.println("AbstractSecs1OnTcpIpMultiClientCommunicator.isOpen() - " +
                "opened=" + isOpened + ", channel.isOpen()=" + isChannelOpen + ", result=" + result);

        return result;
    }

}
