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
			System.out.println("ByteAndSecs1MessageQueue.putBytes() - Starting with " + bs.length + " bytes");
			try {
				synchronized (this.sync) {
					for (byte b : bs) {
						this.bb.add(Byte.valueOf(b));
					}
					System.out.println("ByteAndSecs1MessageQueue.putBytes() - Notifying waiting threads");
					this.sync.notifyAll();
				}
				System.out.println("ByteAndSecs1MessageQueue.putBytes() - Completed");
			} catch (Exception e) {
				System.out.println("ByteAndSecs1MessageQueue.putBytes() - Exception: " + e.getMessage());
				e.printStackTrace();
				throw e;
			}
		}

		public void putSecs1Message(Secs1Message message) throws InterruptedException {
			synchronized (this.sync) {
				this.mm.add(new Secs1MessageBlockPack(message));
				this.sync.notifyAll();
			}
		}

		public ByteOrSecs1Message takeByteOrSecs1Message() throws InterruptedException {
			System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Starting");
			try {
				synchronized (this.sync) {
					// 打印队列状态
					System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Queue status: mm.size=" +
						this.mm.size() + ", bb.size=" + this.bb.size());

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
						System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - " + sb.toString());
					}

					for ( ;; ) {
						Secs1MessageBlockPack msgPack = this.mm.poll();
						if (msgPack != null) {
							System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Found message pack");
							return new ByteOrSecs1Message(null, msgPack);
						}

						Byte b = this.pollByte();
						if (b != null) {
							System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Found byte: " +
								String.format("0x%02X", b));
							return new ByteOrSecs1Message(b, null);
						}

						// 再次检查队列状态
						System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Queue empty, mm.size=" +
							this.mm.size() + ", bb.size=" + this.bb.size());

						System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Waiting for data");
						this.sync.wait(5000); // 添加超时，避免无限等待
						System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Woke up from wait");

						// 唤醒后再次检查队列状态
						System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - After wait, mm.size=" +
							this.mm.size() + ", bb.size=" + this.bb.size());
					}
				}
			} catch (Exception e) {
				System.out.println("ByteAndSecs1MessageQueue.takeByteOrSecs1Message() - Exception: " + e.getMessage());
				e.printStackTrace();
				throw e;
			}
		}

		public Byte pollByte() {
			Byte b = this.bb.poll();
			if (b != null) {
				System.out.println("ByteAndSecs1MessageQueue.pollByte() - Polled byte: " + String.format("0x%02X", b));
			}
			return b;
		}

		public Byte pollByte(TimeoutGettable timeout) throws InterruptedException {
			System.out.println("ByteAndSecs1MessageQueue.pollByte(timeout) - Polling with timeout: " + timeout);
			Byte b = timeout.blockingQueuePoll(this.bb);
			if (b != null) {
				System.out.println("ByteAndSecs1MessageQueue.pollByte(timeout) - Polled byte: " + String.format("0x%02X", b));
			} else {
				System.out.println("ByteAndSecs1MessageQueue.pollByte(timeout) - Timeout occurred, no byte available");
			}
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
		System.out.println("AbstractSecs1CircuitFacade.putBytes() - Starting with " + bs.length + " bytes");
		try {
			this.queue.putBytes(bs);
			System.out.println("AbstractSecs1CircuitFacade.putBytes() - Completed");
		} catch (Exception e) {
			System.out.println("AbstractSecs1CircuitFacade.putBytes() - Exception: " + e.getMessage());
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

		System.out.println("AbstractSecs1CircuitFacade.enter() - Starting");

		try {
			ByteOrSecs1Message v = this.queue.takeByteOrSecs1Message();

			System.out.println("AbstractSecs1CircuitFacade.enter() - Received message from queue: " +
				(v.byteValue != null ? "byte " + String.format("0x%02X", v.byteValue) : "message pack"));

			final Secs1MessageBlockPack pack = v.messagePack;

		if (pack == null) {

			System.out.println("AbstractSecs1CircuitFacade.enter() - No message pack");

			if (v.isENQ()) {

				System.out.println("AbstractSecs1CircuitFacade.enter() - Received ENQ");

				try {
					System.out.println("AbstractSecs1CircuitFacade.enter() - Calling receiving()");
					this.receiving();
				}
				catch (Secs1Exception e) {
					System.out.println("AbstractSecs1CircuitFacade.enter() - Exception in receiving(): " + e.getMessage());
					this.comm.offerThrowableToLog(e);
				}
			} else {
				System.out.println("AbstractSecs1CircuitFacade.enter() - Received byte: " +
					(v.byteValue != null ? String.format("0x%02X", v.byteValue) : "null"));
			}

		} else {

			System.out.println("AbstractSecs1CircuitFacade.enter() - Processing message pack");

			try {

				for (int retry = 0; retry <= this.comm.config().retry().intValue();) {

					System.out.println("AbstractSecs1CircuitFacade.enter() - Sending ENQ, retry: " + retry);
					this.sendByte(ENQ);

					for ( ;; ) {

						System.out.println("AbstractSecs1CircuitFacade.enter() - Waiting for response");
						Byte b = this.queue.pollByte(this.comm.config().timeout().t2());

						if (b == null) {

							System.out.println("AbstractSecs1CircuitFacade.enter() - T2 timeout");
							this.comm.offerThrowableToLog(new Secs1RetryCountUpException(retry));
							retry += 1;
							break;

						} else if (b.byteValue() == ENQ && ! this.comm.config().isMaster().booleanValue()) {

							System.out.println("AbstractSecs1CircuitFacade.enter() - Received ENQ from other side");
							try {
								this.receiving();
							}
							catch (SecsException e) {
								System.out.println("AbstractSecs1CircuitFacade.enter() - Exception in receiving(): " + e.getMessage());
								this.comm.offerThrowableToLog(e);
							}

							retry = 0;
							pack.reset();
							break;

						} else if (b.byteValue() == EOT) {

							System.out.println("AbstractSecs1CircuitFacade.enter() - Received EOT");
							if (this.sending(pack.present())) {

								if (pack.ebit()) {

									this.sendMgr.putSended(pack.message);

									this.comm.notifySendedSecs1MessagePassThrough(pack.message);

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
		System.out.println("AbstractSecs1CircuitFacade.enter() - Completed");
	} catch (Exception e) {
		System.out.println("AbstractSecs1CircuitFacade.enter() - Exception: " + e.getMessage());
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

				if (m != null) {
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
