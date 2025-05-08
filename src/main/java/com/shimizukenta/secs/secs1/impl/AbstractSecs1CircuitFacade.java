package com.shimizukenta.secs.secs1.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.local.property.TimeoutGettable;
import com.shimizukenta.secs.local.property.TimeoutProperty;
import com.shimizukenta.secs.secs1.Secs1Exception;
import com.shimizukenta.secs.secs1.Secs1IllegalLengthByteException;
import com.shimizukenta.secs.secs1.Secs1Message;
import com.shimizukenta.secs.secs1.Secs1MessageBlock;
import com.shimizukenta.secs.secs1.Secs1NotReceiveAckException;
import com.shimizukenta.secs.secs1.Secs1NotReceiveNextBlockEnqException;
import com.shimizukenta.secs.secs1.Secs1RetryCountUpException;
import com.shimizukenta.secs.secs1.Secs1RetryOverException;
import com.shimizukenta.secs.secs1.Secs1SendByteException;
import com.shimizukenta.secs.secs1.Secs1SendMessageException;
import com.shimizukenta.secs.secs1.Secs1SumCheckMismatchException;
import com.shimizukenta.secs.secs1.Secs1TimeoutT1Exception;
import com.shimizukenta.secs.secs1.Secs1TimeoutT2Exception;
import com.shimizukenta.secs.secs1.Secs1TimeoutT3Exception;
import com.shimizukenta.secs.secs1.Secs1TimeoutT4Exception;
import com.shimizukenta.secs.secs1.Secs1WaitReplyMessageException;
import com.shimizukenta.secs.secs1ontcpip.ext.multiclient.AbstractSecs1OnTcpIpMultiClientCommunicator;

public abstract class AbstractSecs1CircuitFacade implements Runnable {

	private static final byte ENQ = (byte)0x05;
	private static final byte EOT = (byte)0x04;
	private static final byte ACK = (byte)0x06;
	private static final byte NAK = (byte)0x15;

	private static Integer systemBytesKey(Secs1Message message) {
		byte[] bs = message.header10Bytes();
		int i = (((int)(bs[6]) << 24) & 0xFF000000)
				| (((int)(bs[7]) << 16) & 0x00FF0000)
				| (((int)(bs[8]) <<  8) & 0x0000FF00)
				| (((int)(bs[9])      ) & 0x000000FF);
		return Integer.valueOf(i);
	}

	private static Integer systemBytesKey(Secs1MessageBlock block) {
		byte[] bs = block.getBytes();
		int i = (((int)(bs[7]) << 24) & 0xFF000000)
				| (((int)(bs[8]) << 16) & 0x00FF0000)
				| (((int)(bs[9]) <<  8) & 0x0000FF00)
				| (((int)(bs[10])     ) & 0x000000FF);
		return Integer.valueOf(i);
	}

	private final class ByteOrSecs1Message {

		public final Byte byteValue;
		public final Secs1MessageBlockPack messagePack;

		public ByteOrSecs1Message(Byte byteValue, Secs1MessageBlockPack messagePack) {
			this.byteValue = byteValue;
			this.messagePack = messagePack;
		}

		public boolean isENQ() {
			return (this.byteValue != null) && (this.byteValue.byteValue() == ENQ);
		}
	}

	private final class Secs1MessageBlockPack {

		public final Secs1Message message;
		private final List<Secs1MessageBlock> blocks;
		private int present;

		public Secs1MessageBlockPack(Secs1Message message) {
			this.message = message;
			this.blocks = new ArrayList<>(message.toBlocks());
			this.present = 0;
		}

		public Secs1MessageBlock present() {
			return this.blocks.get(present);
		}

		public void reset() {
			this.present = 0;
		}

		public void next() {
			++ this.present;
		}

		public boolean ebit() {
			return this.blocks.get(this.present).ebit();
		}
	}

	private final class ByteAndSecs1MessageQueue {

		private final Object sync = new Object();
		private final BlockingQueue<Byte> bb = new LinkedBlockingQueue<>();
		private final Queue<Secs1MessageBlockPack> mm = new LinkedList<>();

		public ByteAndSecs1MessageQueue() {
			/* Nothing */
		}

		public void putBytes(byte[] bs) throws InterruptedException {
			try {
				synchronized (this.sync) {
					for (byte b : bs) {
						this.bb.add(Byte.valueOf(b));
					}
					this.sync.notifyAll();
				}
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}

		public void putSecs1Message(Secs1Message message) throws InterruptedException {
			try {
				synchronized (this.sync) {
					// 打印消息详情
					// 打印消息块信息
					List<Secs1MessageBlock> blocks = message.toBlocks();

					// 创建消息包并添加到队列
					Secs1MessageBlockPack pack = new Secs1MessageBlockPack(message);
					this.mm.add(pack);


					// 通知等待的线程
					this.sync.notifyAll();
				}
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}

		public ByteOrSecs1Message takeByteOrSecs1Message() throws InterruptedException {
			try {
				synchronized (this.sync) {
					// 打印队列状态

					// 如果字节队列不为空，打印前几个字节
					if (!this.bb.isEmpty()) {
						StringBuilder sb = new StringBuilder("Bytes in queue: ");
						int count = 0;
						for (Byte b : this.bb) {
							sb.append(String.format("%02X ", b));
							count++;
							if (count >= 10) {
								sb.append("...");
								break;
							}
						}
					}

					for ( ;; ) {
						Secs1MessageBlockPack msgPack = this.mm.poll();
						if (msgPack != null) {
							return new ByteOrSecs1Message(null, msgPack);
						}

						Byte b = this.pollByte();
						if (b != null) {

							return new ByteOrSecs1Message(b, null);
						}



						this.sync.wait(5000); // 添加超时，避免无限等待

					}
				}
			} catch (Exception e) {
//				e.printStackTrace();
				throw e;
			}
		}

		public Byte pollByte() {
			Byte b = this.bb.poll();

			return b;
		}

		public Byte pollByte(TimeoutGettable timeout) throws InterruptedException {
			Byte b = timeout.blockingQueuePoll(this.bb);

			return b;
		}

		/**
		 * input to bytes.
		 *
		 * @param bs
		 * @param pos start position
		 * @param len size of limit
		 * @param timeout ReadOnlyTimeProperty
		 * @return size of inputs
		 * @throws InterruptedException
		 */
		public int pollBytes(byte[] bs, int pos, int len, TimeoutGettable timeout) throws InterruptedException {

			int r = 0;

			for (int i = pos; i < len; ++i) {
				Byte b = timeout.blockingQueuePoll(this.bb);

				if (b == null) {
					break;
				} else {
					bs[i] = b.byteValue();
				}

				++ r;
			}

			return r;
		}

		public void garbageBytes(TimeoutGettable timeout) throws InterruptedException {
			this.bb.clear();
			for ( ;; ) {
				Byte b = timeout.blockingQueuePoll(this.bb);
				if (b == null) {
					return;
				}
			}
		}
	}

	private final class Secs1SendMessageManager {

		private final Map<Integer, Result> map = new HashMap<>();

		public Secs1SendMessageManager() {
			/* Nothing */
		}

		public void enter(Secs1Message message) {
			synchronized (this.map) {
				this.map.put(systemBytesKey(message), new Result());
			}
		}

		public void exit(Secs1Message message) {
			synchronized (this.map) {
				this.map.remove(systemBytesKey(message));
			}
		}

		private Result getResult(Integer key) {
			synchronized (this.map) {
				return this.map.get(key);
			}
		}

		private Result getResult(Secs1Message message) {
			return this.getResult(systemBytesKey(message));
		}

		public void waitUntilSended(Secs1Message message)
				throws Secs1SendMessageException, InterruptedException {

			final Result r = getResult(message);

			if (r == null) {
				throw new IllegalStateException("message not enter");
			}

			synchronized (r) {
				for ( ;; ) {
					if (r.isSended()) {
						return;
					}

					Secs1Exception e = r.except();
					if (e != null) {
						throw new Secs1SendMessageException(message, e);
					}

					r.wait();
				}
			}
		}

		public void putSended(Secs1Message message) {
			final Result r = this.getResult(message);
			if (r != null) {
				r.setSended();
			}
		}

		public void putException(Secs1Message message, Secs1Exception e) {
			final Result r = this.getResult(message);
			if (r != null) {
				r.setExcept(e);
			}
		}

		private class Result {

			private boolean sended;
			private Secs1Exception except;

			public Result() {
				this.sended = false;
				this.except = null;
			}

			public void setSended() {
				synchronized (this) {
					this.sended = true;
					this.notifyAll();
				}
			}

			public boolean isSended() {
				synchronized (this) {
					return this.sended;
				}
			}

			public void setExcept(Secs1Exception e) {
				synchronized (this) {
					this.except = e;
					this.notifyAll();
				}
			}

			public Secs1Exception except() {
				synchronized (this) {
					return this.except;
				}
			}
		}
	}

	private final class Secs1TransactionManager {

		private final Map<Integer, Pack> map = new HashMap<>();

		public Secs1TransactionManager() {
			/* Nothing */
		}

		public void enter(Secs1Message primaryMsg) {
			synchronized (this.map) {
				this.map.put(systemBytesKey(primaryMsg), new Pack());
			}
		}

		public void exit(Secs1Message primaryMsg) {
			synchronized (this.map) {
				this.map.remove(systemBytesKey(primaryMsg));
			}
		}

		private Pack getPack(Integer key) {
			synchronized (this.map) {
				return this.map.get(key);
			}
		}

		private Pack getPack(Secs1Message message) {
			return this.getPack(systemBytesKey(message));
		}

		public Secs1Message waitReply(Secs1Message message, TimeoutProperty timeout) throws InterruptedException {

			final Pack p = getPack(message);

			if (p == null) {
				return null;
			}

			synchronized (p) {

				{
					Secs1Message r = p.replyMsg();
					if (r != null) {
						return r;
					}
				}

				for ( ;; ) {

					timeout.wait(p);

					Secs1Message r = p.replyMsg();

					if (r != null) {
						return r;
					}

					if (! p.isTimerResetted()) {
						return null;
					}
				}
			}
		}

		public Secs1Message put(Secs1Message message) {

			final Pack p = this.getPack(message);

			if (p == null) {

				return message;

			} else {

				p.putReplyMsg(message);
				return null;
			}
		}

		public void resetTimer(Secs1MessageBlock block) {

			Pack p = getPack(systemBytesKey(block));

			if (p != null) {
				p.resetTimer();
			}
		}

		private final class Pack {

			private boolean timerResetted;
			private Secs1Message replyMsg;

			public Pack() {
				this.timerResetted = false;
				this.replyMsg = null;
			}

			public void resetTimer() {
				synchronized (this) {
					this.timerResetted = true;
					this.notifyAll();
				}
			}

			public boolean isTimerResetted() {
				synchronized (this) {
					boolean f = this.timerResetted;
					this.timerResetted = false;
					return f;
				}
			}

			public void putReplyMsg(Secs1Message msg) {
				synchronized (this) {
					this.replyMsg = msg;
					this.notifyAll();
				}
			}

			public Secs1Message replyMsg() {
				synchronized (this) {
					return this.replyMsg;
				}
			}
		}
	}


	private final ByteAndSecs1MessageQueue queue = new ByteAndSecs1MessageQueue();
	private final Secs1SendMessageManager sendMgr = new Secs1SendMessageManager();
	private final Secs1TransactionManager transMgr = new Secs1TransactionManager();

	private final AbstractSecs1Communicator comm;

	public AbstractSecs1CircuitFacade(AbstractSecs1Communicator communicator) {
		this.comm = communicator;
	}

	@Override
	public void run() {
		try {
			for ( ;; ) {
				this.enter();
			}
		}
		catch (InterruptedException ignore) {
		}
	}

	public void putBytes(byte[] bs) throws InterruptedException {
		try {
			// 打印接收到的数据的十六进制表示
			StringBuilder hexData = new StringBuilder();
			for (byte b : bs) {
				hexData.append(String.format("%02X ", b));
				if (hexData.length() > 100) {
					hexData.append("..."); // 限制输出长度
					break;
				}
			}



			// 检查是否是SECS消息块
			if (bs.length >= 10) {
				// 打印消息头部信息
				if (bs.length >= 11) { // 至少需要包含长度字节和头部
					int len = (int)(bs[0]) & 0x000000FF;

					if (len >= 10 && len <= 254 && bs.length >= len + 3) {
						// 打印设备ID和其他头部信息
						int deviceId = ((int)(bs[1]) << 8) | ((int)(bs[2]) & 0xFF);
						boolean ebit = (bs[3] & 0x80) != 0;
						int blockNumber = bs[3] & 0x7F;



						// 如果有足够的数据，打印SystemBytes
						if (bs.length >= 11) {
							int systemBytes = (((int)(bs[7]) << 24) & 0xFF000000)
								| (((int)(bs[8]) << 16) & 0x00FF0000)
								| (((int)(bs[9]) << 8) & 0x0000FF00)
								| (((int)(bs[10])) & 0x000000FF);

						}
					}
				}
			}

			// 将数据添加到队列
			this.queue.putBytes(bs);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	private void sendBytes(byte[] bs) throws Secs1SendByteException, InterruptedException {
		this.comm.sendBytes(bs);
	}

	private void sendByte(byte b) throws Secs1SendByteException, InterruptedException {
		this.sendBytes(new byte[] {b});
	}

	public Optional<Secs1Message> send(Secs1Message msg)
			throws Secs1SendMessageException, Secs1WaitReplyMessageException, Secs1Exception,
			InterruptedException {

		try {

			this.sendMgr.enter(msg);

			this.comm.notifyTrySendSecs1MessagePassThrough(msg);

			if (msg.wbit() && msg.isValidBlocks()) {

				try {
					this.transMgr.enter(msg);

					this.queue.putSecs1Message(msg);

					this.sendMgr.waitUntilSended(msg);

					Secs1Message r = this.transMgr.waitReply(
							msg,
							this.comm.config().timeout().t3());

					if (r == null) {

						throw new Secs1TimeoutT3Exception(msg);

					} else {

						return Optional.of(r);
					}
				}
				finally {

					this.transMgr.exit(msg);
				}

			} else {

				this.queue.putSecs1Message(msg);

				this.sendMgr.waitUntilSended(msg);

				return Optional.empty();
			}
		}
		catch (Secs1SendMessageException e) {
			this.comm.offerThrowableToLog(e);
			throw e;
		}
		catch (Secs1WaitReplyMessageException e) {
			this.comm.offerThrowableToLog(e);
			throw e;
		}
		finally {
			this.sendMgr.exit(msg);
		}
	}

	private void enter() throws InterruptedException {


		try {
			ByteOrSecs1Message v = this.queue.takeByteOrSecs1Message();



			final Secs1MessageBlockPack pack = v.messagePack;

		if (pack == null) {


			if (v.isENQ()) {


				try {
					this.receiving();
				}
				catch (Secs1Exception e) {
					this.comm.offerThrowableToLog(e);
				}
			} else {

			}

		} else {


			try {

				for (int retry = 0; retry <= this.comm.config().retry().intValue();) {

					this.sendByte(ENQ);

					for ( ;; ) {

						Byte b = this.queue.pollByte(this.comm.config().timeout().t2());

						if (b == null) {

							this.comm.offerThrowableToLog(new Secs1RetryCountUpException(retry));
							retry += 1;
							break;

						} else if (b.byteValue() == ENQ && ! this.comm.config().isMaster().booleanValue()) {

							try {
								this.receiving();
							}
							catch (SecsException e) {
								this.comm.offerThrowableToLog(e);
							}

							retry = 0;
							pack.reset();
							break;

						} else if (b.byteValue() == EOT) {

							if (this.sending(pack.present())) {
								Secs1Message message = pack.message;
								if (pack.ebit()) {
									boolean bMultiClient = this.comm instanceof AbstractSecs1OnTcpIpMultiClientCommunicator;
									AbstractSecs1OnTcpIpMultiClientCommunicator comm1 = null;
									if( bMultiClient ){
										comm1 = (AbstractSecs1OnTcpIpMultiClientCommunicator) this.comm;
										message.setSourceAddress(comm1.getRemoteSocketAddress());
										message.setOutBound( true );

									}

									this.sendMgr.putSended(message);

									this.comm.notifySendedSecs1MessagePassThrough(message);

									return;

								} else {

									pack.next();
									retry = 0;
									break;
								}

							} else {

								this.comm.offerThrowableToLog(new Secs1RetryCountUpException(retry));
								retry += 1;
								break;
							}
						}
					}
				}

				this.sendMgr.putException(
						pack.message,
						new Secs1RetryOverException());
			}
			catch (Secs1Exception e) {
				this.sendMgr.putException(pack.message, e);
				this.comm.offerThrowableToLog(e);
			}
		}
	} catch (Exception e) {
		e.printStackTrace();
		throw e;
	}
	}

	private boolean sending(Secs1MessageBlock block)
			throws Secs1Exception, InterruptedException {

		this.comm.secs1LogObserver().offerTrySendSecs1MessageBlockPassThrough(block);

		this.sendBytes(block.getBytes());

		Byte b = this.queue.pollByte(this.comm.config().timeout().t2());

		if (b == null) {

			this.comm.offerThrowableToLog(new Secs1TimeoutT2Exception("ACK"));
			return false;

		} else if (b.byteValue() == ACK) {

			this.comm.secs1LogObserver().offerSendedSecs1MessageBlockPassThrough(block);
			return true;

		} else {

			this.comm.offerThrowableToLog(new Secs1NotReceiveAckException(block,b));
			return false;
		}
	}

	private final LinkedList<AbstractSecs1MessageBlock> cacheBlocks = new LinkedList<>();

	private void receiving() throws Secs1Exception, InterruptedException {

		this.sendByte(EOT);

		byte[] bs = new byte[257];

		{
			int r = this.queue.pollBytes(bs, 0, 1, this.comm.config().timeout().t2());

			if (r <= 0) {
				this.sendByte(NAK);
				this.comm.offerThrowableToLog(new Secs1TimeoutT2Exception("LengthByte"));
				return;
			}
		}

		int len = (int)(bs[0]) & 0x000000FF;

		{
			if (len < 10 || len > 254) {
				this.queue.garbageBytes(this.comm.config().timeout().t1());
				this.sendByte(NAK);
				this.comm.offerThrowableToLog(new Secs1IllegalLengthByteException(len));
				return;
			}

			for (int pos = 1, m = (len + 3); pos < m;) {

				int r = this.queue.pollBytes(bs, pos, m, this.comm.config().timeout().t1());

				if (r <= 0) {
					this.sendByte(NAK);
					this.comm.offerThrowableToLog(new Secs1TimeoutT1Exception(pos));
					return;
				}

				pos += r;
			}
		}

		AbstractSecs1MessageBlock block = new AbstractSecs1MessageBlock(Arrays.copyOf(bs, (len + 3))) {

			private static final long serialVersionUID = -1187993676063154279L;
		};

		if (block.checkSum()) {

			this.sendByte(ACK);

		} else {

			this.queue.garbageBytes(this.comm.config().timeout().t1());
			this.sendByte(NAK);
			this.comm.offerThrowableToLog(new Secs1SumCheckMismatchException());
			return;
		}

		this.comm.secs1LogObserver().offerReceiveSecs1MessageBlockPassThrough(block);

		if (this.comm.config().isCheckMessageBlockDeviceId().booleanValue()) {
			if (block.deviceId() != this.comm.config().deviceId().intValue()) {
				return;
			}
		}

		if (this.cacheBlocks.isEmpty()) {

			this.cacheBlocks.add(block);

		} else {

			AbstractSecs1MessageBlock prev = this.cacheBlocks.getLast();

			if (prev.equalsSystemBytes(block)) {

				if (prev.isNextBlock(block)) {
					this.cacheBlocks.add(block);
				}

			} else {

				this.cacheBlocks.clear();
				this.cacheBlocks.add(block);
			}
		}

		if (block.ebit()) {

			try {
				AbstractSecs1Message s1msg = Secs1MessageBuilder.buildFromBlocks(cacheBlocks);
				Secs1Message m = this.transMgr.put(s1msg);
//                s1msg.setSourceAddress(this.comm.g);
				boolean bMultiClient = this.comm instanceof AbstractSecs1OnTcpIpMultiClientCommunicator;
				AbstractSecs1OnTcpIpMultiClientCommunicator comm1 = null;
				if( bMultiClient ){
					 comm1 = (AbstractSecs1OnTcpIpMultiClientCommunicator) this.comm;
					s1msg.setSourceAddress(comm1.getRemoteSocketAddress());

				}

//                 m.setSourceAddress( );
				if (m != null) {
					if( bMultiClient ){
						m.setSourceAddress(comm1.getRemoteSocketAddress());
					}
					this.comm.secs1MessageReceiveObserver().putSecs1Message(m);
				}

				this.comm.notifyReceiveSecs1MessagePassThrough(s1msg);
			}
			finally {
				this.cacheBlocks.clear();
			}

		} else {

			this.transMgr.resetTimer(block);

			Byte b = this.queue.pollByte(this.comm.config().timeout().t4());

			if (b == null) {

				this.comm.offerThrowableToLog(new Secs1TimeoutT4Exception(block));

			} else if (b.byteValue() == ENQ) {

				this.receiving();

			} else {

				this.comm.offerThrowableToLog(new Secs1NotReceiveNextBlockEnqException(block, b));
			}
		}
	}

}
