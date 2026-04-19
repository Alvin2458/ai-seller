/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ai.sellar.agent.application.service;

import com.ai.sellar.agent.infrastructure.freeswitch.FreeSwitchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound Call Service - Manages outbound calls via FreeSWITCH
 * 
 * @author buvidk
 * @since 2026-04-12
 */
@Service
public class OutboundCallService {

    private static final Logger log = LoggerFactory.getLogger(OutboundCallService.class);

    private final FreeSwitchClient freeSwitchClient;
    private final VoiceAgentPipeline voiceAgentPipeline;

    @Value("${freeswitch.gateway:default}")
    private String defaultGateway;

    @Value("${freeswitch.caller-id:1000}")
    private String defaultCallerId;

    // Track active calls: callId -> CallSession
    private final Map<String, CallSession> activeCalls = new ConcurrentHashMap<>();

    public OutboundCallService(FreeSwitchClient freeSwitchClient, VoiceAgentPipeline voiceAgentPipeline) {
        this.freeSwitchClient = freeSwitchClient;
        this.voiceAgentPipeline = voiceAgentPipeline;
    }

    /**
     * Make an outbound call to an extension number
     * 
     * @param extensionNumber Destination extension number
     * @return Call ID for tracking
     */
    public CompletableFuture<CallResult> makeOutboundCall(String extensionNumber) {
        return makeOutboundCall(extensionNumber, defaultCallerId);
    }

    /**
     * Make an outbound call to an extension number with custom caller ID
     * 
     * @param extensionNumber Destination extension number
     * @param callerId Caller ID to display
     * @return Call ID for tracking
     */
    public CompletableFuture<CallResult> makeOutboundCall(String extensionNumber, String callerId) {
        String callId = UUID.randomUUID().toString();
        
        log.info("Initiating outbound call [{}]: {} (extension)", callId, extensionNumber);
        
        CallSession session = new CallSession(callId, extensionNumber, callerId);
        activeCalls.put(callId, session);

        return freeSwitchClient.makeOutboundCallToExtension(extensionNumber, callerId)
            .thenApply(response -> {
                log.info("Outbound call initiated [{}]: {}", callId, response);
                session.setStatus(CallStatus.INITIATED);
                return new CallResult(callId, extensionNumber, CallStatus.INITIATED, response);
            })
            .exceptionally(e -> {
                log.error("Failed to initiate outbound call [{}]", callId, e);
                session.setStatus(CallStatus.FAILED);
                activeCalls.remove(callId);
                return new CallResult(callId, extensionNumber, CallStatus.FAILED, e.getMessage());
            });
    }

    /**
     * Hangup an active call
     * 
     * @param callId Call ID to hangup
     * @return Result of hangup operation
     */
    public CallResult hangupCall(String callId) {
        CallSession session = activeCalls.get(callId);
        if (session == null) {
            return new CallResult(callId, null, CallStatus.NOT_FOUND, "Call not found");
        }

        try {
            // Send hangup command to FreeSWITCH using uuid_kill
            String hangupCmd = String.format("uuid_kill %s", callId);
            freeSwitchClient.sendApiCommand("api", hangupCmd);

            session.setStatus(CallStatus.HUNG_UP);
            activeCalls.remove(callId);

            log.info("Call hung up [{}]", callId);
            return new CallResult(callId, session.getPhoneNumber(), CallStatus.HUNG_UP, "Call terminated");
        } catch (Exception e) {
            log.error("Failed to hangup call [{}]", callId, e);
            return new CallResult(callId, session.getPhoneNumber(), CallStatus.ERROR, e.getMessage());
        }
    }

    /**
     * Get call status
     * 
     * @param callId Call ID to check
     * @return Call session information
     */
    public CallSession getCallStatus(String callId) {
        return activeCalls.get(callId);
    }

    /**
     * Get all active calls
     */
    public Map<String, CallSession> getActiveCalls() {
        return Map.copyOf(activeCalls);
    }

    /**
     * Call session tracking
     */
    public static class CallSession {
        private final String callId;
        private final String phoneNumber;
        private final String callerId;
        private CallStatus status;
        private long startTime;

        public CallSession(String callId, String phoneNumber, String callerId) {
            this.callId = callId;
            this.phoneNumber = phoneNumber;
            this.callerId = callerId;
            this.status = CallStatus.INITIATED;
            this.startTime = System.currentTimeMillis();
        }

        public String getCallId() { return callId; }
        public String getPhoneNumber() { return phoneNumber; }
        public String getCallerId() { return callerId; }
        public CallStatus getStatus() { return status; }
        public void setStatus(CallStatus status) { this.status = status; }
        public long getStartTime() { return startTime; }
        public long getDuration() { return System.currentTimeMillis() - startTime; }
    }

    /**
     * Call result
     */
    public record CallResult(String callId, String phoneNumber, CallStatus status, String message) {}

    /**
     * Call status enum
     */
    public enum CallStatus {
        INITIATED,
        RINGING,
        ANSWERED,
        ACTIVE,
        HUNG_UP,
        FAILED,
        ERROR,
        NOT_FOUND
    }
}
