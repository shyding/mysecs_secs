package com.shimizukenta.tmp.secs;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client state class for storing client-specific information
 */
public class ClientState {

    // Client ID
    private final int clientId;

    // Device ID
    private int deviceId = 0;

    // Last used system bytes
    private byte[] lastSystemBytes = new byte[4];

    // Client states
    private String controlState = "REMOTE";    // Control state
    private String runningState = "IDLE";      // Running state
    private String communicationState = "COMMUNICATING"; // Communication state

    // HSMS Session ID
    private String sessionId = null;

    // HSMS Session state
    private HsmsSessionState hsmsSessionState = HsmsSessionState.NOT_CONNECTED;

    // Last activity time
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    // Report ID counter
    private final AtomicInteger reportIdCounter = new AtomicInteger(1);

    // SystemBytes counter for generating unique SystemBytes values
    private final AtomicInteger systemBytesCounter = new AtomicInteger(1);

    // Last received SystemBytes from client
    private byte[] lastReceivedSystemBytes = new byte[]{(byte)0x80, 0, 0, 1};

    // Report definitions map
    private final Map<Integer, String> reportDefinitions = new HashMap<>();

    /**
     * Constructor
     *
     * @param clientId Client ID
     */
    public ClientState(int clientId) {
        this.clientId = clientId;
    }

    /**
     * Get client ID
     *
     * @return Client ID
     */
    public int getClientId() {
        return clientId;
    }

    /**
     * Get device ID
     *
     * @return Device ID
     */
    public int getDeviceId() {
        return deviceId;
    }

    /**
     * Set device ID
     *
     * @param deviceId Device ID
     */
    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * Get last used system bytes
     *
     * @return System bytes
     */
    public byte[] getLastSystemBytes() {
        return lastSystemBytes;
    }

    /**
     * Set last used system bytes
     *
     * @param systemBytes System bytes
     */
    public void setLastSystemBytes(byte[] systemBytes) {
        if (systemBytes != null && systemBytes.length == 4) {
            System.arraycopy(systemBytes, 0, this.lastSystemBytes, 0, 4);
        }
    }

    /**
     * Get control state
     *
     * @return Control state
     */
    public String getControlState() {
        return controlState;
    }

    /**
     * Set control state
     *
     * @param controlState Control state
     */
    public void setControlState(String controlState) {
        this.controlState = controlState;
    }

    /**
     * Get running state
     *
     * @return Running state
     */
    public String getRunningState() {
        return runningState;
    }

    /**
     * Set running state
     *
     * @param runningState Running state
     */
    public void setRunningState(String runningState) {
        this.runningState = runningState;
    }

    /**
     * Get communication state
     *
     * @return Communication state
     */
    public String getCommunicationState() {
        return communicationState;
    }

    /**
     * Set communication state
     *
     * @param communicationState Communication state
     */
    public void setCommunicationState(String communicationState) {
        this.communicationState = communicationState;
    }

    /**
     * Get next report ID
     *
     * @return Report ID
     */
    public int getNextReportId() {
        return reportIdCounter.getAndIncrement();
    }

    /**
     * Define report
     *
     * @param reportId Report ID
     * @param definition Report definition
     */
    public void defineReport(int reportId, String definition) {
        reportDefinitions.put(reportId, definition);
    }

    /**
     * Get report definition
     *
     * @param reportId Report ID
     * @return Report definition
     */
    public String getReportDefinition(int reportId) {
        return reportDefinitions.get(reportId);
    }

    /**
     * Get all report definitions
     *
     * @return Report definitions map
     */
    public Map<Integer, String> getReportDefinitions() {
        return new HashMap<>(reportDefinitions);
    }

    /**
     * Get session ID
     *
     * @return Session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Set session ID
     *
     * @param sessionId Session ID
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * HSMS Session state enum
     */
    public enum HsmsSessionState {
        NOT_CONNECTED,  // 未连接
        NOT_SELECTED,   // 已连接但未选择
        SELECTED        // 已选择
    }

    /**
     * Get HSMS session state
     *
     * @return HSMS session state
     */
    public HsmsSessionState getHsmsSessionState() {
        return hsmsSessionState;
    }

    /**
     * Set HSMS session state
     *
     * @param hsmsSessionState HSMS session state
     */
    public void setHsmsSessionState(HsmsSessionState hsmsSessionState) {
        this.hsmsSessionState = hsmsSessionState;
    }

    /**
     * Update last activity time to current time
     */
    public void updateLastActivityTime() {
        lastActivityTime.set(System.currentTimeMillis());
    }

    /**
     * Get next SystemBytes value
     *
     * @return Next SystemBytes value as byte array
     */
    public byte[] getNextSystemBytes() {
        int nextId = systemBytesCounter.getAndIncrement();
        if (nextId > 0x7FFFFFFF) { // 防止溢出，重置为1
            systemBytesCounter.set(1);
            nextId = 1;
        }

        // 创建新的SystemBytes
        byte[] newSystemBytes = java.nio.ByteBuffer.allocate(4).putInt(nextId).array();
        // 确保首字节为0x80
        newSystemBytes[0] = (byte)0x80;
        return newSystemBytes;
    }

    /**
     * Get last received SystemBytes from client
     *
     * @return Last received SystemBytes
     */
    public byte[] getLastReceivedSystemBytes() {
        return lastReceivedSystemBytes;
    }

    /**
     * Set last received SystemBytes from client
     *
     * @param systemBytes SystemBytes to set
     */
    public void setLastReceivedSystemBytes(byte[] systemBytes) {
        if (systemBytes != null && systemBytes.length == 4) {
            this.lastReceivedSystemBytes = systemBytes.clone();
        }
    }

    /**
     * Get last activity time
     *
     * @return Last activity time in milliseconds
     */
    public long getLastActivityTime() {
        return lastActivityTime.get();
    }
}
