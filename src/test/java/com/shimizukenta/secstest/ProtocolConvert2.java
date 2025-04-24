package com.shimizukenta.secstest;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessageReceiveBiListener;
import com.shimizukenta.secs.gem.*;
import com.shimizukenta.secs.hsms.HsmsConnectionMode;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpCommunicatorConfig;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicator;
import com.shimizukenta.secs.secs1ontcpip.Secs1OnTcpIpReceiverCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;
import com.shimizukenta.secstestutil.Secs1OnTcpIpHsmsSsConverter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class ProtocolConvert2 {

	public ProtocolConvert2() {
		/* Nothing */
	}
	
	private static final int deviceId = 10;
	private static final SocketAddress secs1Addr = new InetSocketAddress("127.0.0.1", 23000);
	private static final SocketAddress hsmsSsAddr = new InetSocketAddress("127.0.0.1", 5000);
	
	public static void main(String[] args) {
		

			
//			final Secs1OnTcpIpCommunicatorConfig secs1Side = new Secs1OnTcpIpCommunicatorConfig();
			final HsmsSsCommunicatorConfig hsmsSsSide = new HsmsSsCommunicatorConfig();
//			final Secs1OnTcpIpReceiverCommunicatorConfig equipConfig = new Secs1OnTcpIpReceiverCommunicatorConfig();
			final HsmsSsCommunicatorConfig hostConfig = new HsmsSsCommunicatorConfig();
			
//			secs1Side.deviceId(deviceId);
//			secs1Side.isEquip(false);
//			secs1Side.isMaster(false);
//			secs1Side.socketAddress(secs1Addr);
//			secs1Side.timeout().t1( 1.0F);
//			secs1Side.timeout().t2(15.0F);
//			secs1Side.timeout().t3(45.0F);
//			secs1Side.timeout().t4(45.0F);
//			secs1Side.retry(3);
//			secs1Side.reconnectSeconds(5.0F);
//			secs1Side.logSubjectHeader("SECS-I-Side: ");
//			secs1Side.name("Secs1Side");

		hostConfig.sessionId(deviceId);
		hostConfig.isEquip(false);
		hostConfig.socketAddress(hsmsSsAddr);
		hostConfig.connectionMode(HsmsConnectionMode.PASSIVE);
		hostConfig.timeout().t3(45.0F);
		hostConfig.timeout().t6( 5.0F);
		hostConfig.timeout().t7(10.0F);
		hostConfig.timeout().t8( 5.0F);
		hostConfig.notLinktest();
		hostConfig.rebindIfPassive(5.0F);
		hostConfig.logSubjectHeader("HSMS-SS-Passive-Side: ");
		hostConfig.name("HsmsSsPassiveSide");
			


					try (
							SecsCommunicator host = HsmsSsCommunicator.newInstance(hostConfig);
							) {
						
						host.addSecsLogListener(ProtocolConvert2::echo);
						host.addSecsMessageReceiveBiListener(hostRecvListener());
						
						host.open();
						

						host.waitUntilCommunicatable();
						

					} catch (Exception e) {
                       echo(e);
                    }


	
}
	
	private static SecsMessageReceiveBiListener equipRecvListener() {
		
		return (msg, comm) -> {
			
			int strm = msg.getStream();
			int func = msg.getFunction();
			boolean wbit = msg.wbit();
//			Secs2 body = msg.secs2();
			
			try {
				
				switch ( strm ) {
				case 1: {
					
					switch ( func ) {
					case 1: {
						
						if ( wbit ) {
							comm.gem().s1f2(msg);
						}
						break;
					}
					case 13: {
						
						if ( wbit ) {
							comm.gem().s1f14(msg, COMMACK.OK);
						}
						break;
					}
					case 15: {
						
						if ( wbit ) {
							comm.gem().s1f16(msg);
						}
						break;
					}
					case 17: {
						
						if ( wbit ) {
							comm.gem().s1f18(msg, ONLACK.OK);
						}
						break;
					}
					default: {
						
						if ( wbit ) {
							comm.send(msg, strm, 0, false);
						}
						
						comm.gem().s9f5(msg);
					}
					}
					break;
				}
				case 2: {
					
					switch ( func ) {
					case 17: {
						
						if ( wbit ) {
							comm.gem().s2f18Now(msg);
						}
						break;
					}
					case 31: {
						
						if ( wbit ) {
							comm.gem().s2f32(msg, TIACK.OK);
						}
						break;
					}
					default: {
						
						if ( wbit ) {
							comm.send(msg, strm, 0, false);
						}
						
						comm.gem().s9f5(msg);
					}
					}
					break;
				}
				default: {
					
					if ( wbit ) {
						comm.send(msg, 0, 0, false);
					}
					
					comm.gem().s9f3(msg);
				}
				}
			}
			catch ( InterruptedException ignore ) {
			}
			catch ( SecsException e ) {
				echo(e);
			}
		};
	}
	
	private static SecsMessageReceiveBiListener hostRecvListener() {
		
		return (msg, comm) -> {
			int strm = msg.getStream();
			int func = msg.getFunction();
			boolean wbit = msg.wbit();
//			Secs2 body = msg.secs2();
			
			try {
				
				switch ( strm ) {
				case 1: {
					
					switch ( func ) {
					case 1: {
						
						if ( wbit ) {
							comm.gem().s1f2(msg);
						}
						break;
					}
					case 13: {
						
						if ( wbit ) {
							comm.gem().s1f14(msg, COMMACK.OK);
						}
						break;
					}
					case 15: {
						
						if ( wbit ) {
							comm.gem().s1f16(msg);
						}
						break;
					}
					case 17: {
						
						if ( wbit ) {
							comm.gem().s1f18(msg, ONLACK.OK);
						}
						break;
					}
					default: {
						
						if ( wbit ) {
							comm.send(msg, strm, 0, false);
						}
					}
					}
					break;
				}
				case 2: {
					
					switch ( func ) {
					case 17: {
						
						if ( wbit ) {
							comm.gem().s2f18Now(msg);
						}
						break;
					}
					case 31: {
						
						if ( wbit ) {
							comm.gem().s2f32(msg, TIACK.OK);
						}
						break;
					}
					default: {
						
						if ( wbit ) {
							comm.send(msg, strm, 0, false);
						}
					}
					}
					break;
				}
				case 5: {
					
					switch ( func ) {
					case 1: {
						
						if ( wbit ) {
							comm.gem().s5f2(msg, ACKC5.OK);
						}
						break;
					}
					default: {
						
						if ( wbit ) {
							comm.send(msg, strm, 0, false);
						}
					}
					}
					break;
				}
				case 6: {
					
					switch ( func ) {
					case 4: {
						
						if ( wbit ) {
							comm.gem().s6f4(msg, ACKC6.OK);
						}
						break;
					}
					case 12: {
						
						if ( wbit ) {
							comm.gem().s6f12(msg, ACKC6.OK);
						}
						break;
					}
					default: {
						
						if ( wbit ) {
							comm.send(msg, strm, 0, false);
						}
					}
					}
					break;
				}
				default: {
					
					if ( wbit ) {
						comm.send(msg, 0, 0, false);
					}
				}
				}
			}
			catch ( InterruptedException ignore ) {
			}
			catch ( SecsException e ) {
				echo(e);
			}
		};
	}
	
	private static Object syncStaticEcho = new Object();
	
	private static void echo(Object o) {
		
		synchronized ( syncStaticEcho ) {
			
			if ( o instanceof Throwable) {
				
				try (
						StringWriter sw = new StringWriter();
						) {
					
					try (
							PrintWriter pw = new PrintWriter(sw);
							) {
						
						((Throwable) o).printStackTrace(pw);
						pw.flush();
						
						System.out.println(sw.toString());
					}
				}
				catch ( IOException e ) {
					e.printStackTrace();
				}
				
			} else {
				
				System.out.println(o);
			}
			
			System.out.println();
		}
	}
	
}
