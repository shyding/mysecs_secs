package com.shimizukenta.secs.secs1ontcpip.ext.multiclient;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.SecsSendMessageException;
import com.shimizukenta.secs.SecsWaitReplyMessageException;
import com.shimizukenta.secs.secs1.Secs1Communicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpLogObservable;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;

/**
 * This interface is implementation of SECS-I (SEMI-E4) on TCP/IP for multiple clients.
 *
 * <ul>
 * <li>To create new instance, {@link #newInstance(Secs1OnTcpIpReceiverCommunicatorConfig, AsynchronousSocketChannel)}</li>
 * <li>To create new instance and open, {@link #open(Secs1OnTcpIpReceiverCommunicatorConfig, AsynchronousSocketChannel)}</li>
 * </ul>
 *
 */
public interface Secs1OnTcpIpMultiClientCommunicator extends Secs1Communicator, Secs1OnTcpIpLogObservable {

    /**
     * Get remote socket address of this client connection
     *
     * @return remote socket address
     */
    public SocketAddress getRemoteSocketAddress();

    /**
     * Send SECS message and wait reply
     *
     * @param strm Stream number
     * @param func Function number
     * @param secs2 SECS-II data
     * @return Reply message
     * @throws SecsSendMessageException if send failed
     * @throws SecsWaitReplyMessageException if timeout
     * @throws SecsException if SECS communicate failed
     * @throws InterruptedException if interrupted
     */
    public SecsMessage sendAndWaitReply(int strm, int func, Secs2 secs2)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException;

    /**
     * Send SECS message and wait reply with timeout
     *
     * @param strm Stream number
     * @param func Function number
     * @param secs2 SECS-II data
     * @param timeout Timeout in milliseconds
     * @return Reply message
     * @throws SecsSendMessageException if send failed
     * @throws SecsWaitReplyMessageException if timeout
     * @throws SecsException if SECS communicate failed
     * @throws InterruptedException if interrupted
     */
    public SecsMessage sendAndWaitReply(int strm, int func, Secs2 secs2, long timeout)
            throws SecsSendMessageException, SecsWaitReplyMessageException, SecsException, InterruptedException;

    /**
     * Create SECS-I-on-TCP/IP-MultiClient instance.
     *
     * @param config the SECS-I-on-TCP/IP Receiver config
     * @param channel the client socket channel
     * @return new Secs1OnTcpIpMultiClientCommunicator instance
     * @throws IOException if failed to create instance
     */
    public static Secs1OnTcpIpMultiClientCommunicator newInstance(
            Secs1OnTcpIpReceiverCommunicatorConfig config,
            AsynchronousSocketChannel channel) throws IOException {

        return new AbstractSecs1OnTcpIpMultiClientCommunicator(config, channel) {};
    }

    /**
     * Create SECS-I-on-TCP/IP-MultiClient instance and {@link #open()}.
     *
     * @param config the SECS-I-on-TCP/IP Receiver config
     * @param channel the client socket channel
     * @return new Secs1OnTcpIpMultiClientCommunicator instance
     * @throws IOException if open failed
     */
    public static Secs1OnTcpIpMultiClientCommunicator open(
            Secs1OnTcpIpReceiverCommunicatorConfig config,
            AsynchronousSocketChannel channel) throws IOException {

        final Secs1OnTcpIpMultiClientCommunicator inst = newInstance(config, channel);

        try {
            inst.open();
        }
        catch ( IOException e ) {

            try {
                inst.close();
            }
            catch ( IOException giveup ) {
            }

            throw e;
        }

        return inst;
    }
}
