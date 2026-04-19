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
package com.ai.sellar.agent.infrastructure.freeswitch;

import org.freeswitch.esl.client.transport.event.EslEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FreeSWITCH Event Listener - Handles call events and triggers audio bridge
 *
 * @author buvidk
 * @since 2026-04-19
 */
@Component
public class FreeSwitchEventListener {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchEventListener.class);

    @Value("${freeswitch.audio.bridge-host:192.168.1.238}")
    private String bridgeHost;

    @Value("${freeswitch.audio.bridge-port:8040}")
    private int bridgePort;

    private FreeSwitchClient freeSwitchClient;

    private final Map<String, CallSessionInfo> activeSessions = new ConcurrentHashMap<>();

    public void registerWithClient(FreeSwitchClient client) {
        this.freeSwitchClient = client;
        client.setOnChannelCreate(this::onChannelCreate);
        client.setOnChannelAnswer(this::onChannelAnswer);
        client.setOnChannelHangup(this::onChannelHangup);
        log.info("FreeSwitchEventListener registered with FreeSwitchClient");
    }

    private void onChannelCreate(EslEvent event) {
        try {
            String uuid = getHeader(event, "Unique-ID");
            String caller = getHeader(event, "Caller-Caller-ID-Number");
            String callee = getHeader(event, "Caller-Callee-ID-Number");
            String direction = getHeader(event, "Call-Direction");

            log.info("📞 Channel Created: uuid={}, caller={}, callee={}, direction={}", uuid, caller, callee, direction);
            activeSessions.put(uuid, new CallSessionInfo(uuid, caller, callee, direction));

        } catch (Exception e) {
            log.error("Error handling CHANNEL_CREATE", e);
        }
    }

    private void onChannelAnswer(EslEvent event) {
        try {
            String uuid = getHeader(event, "Unique-ID");
            String caller = getHeader(event, "Caller-Caller-ID-Number");
            String callee = getHeader(event, "Caller-Callee-ID-Number");

            log.info("✅ Channel Answered: uuid={}, caller={}, callee={}", uuid, caller, callee);

            // Start audio recording for AI processing
            startAudioCapture(uuid);

        } catch (Exception e) {
            log.error("Error handling CHANNEL_ANSWER", e);
        }
    }

    private void onChannelHangup(EslEvent event) {
        try {
            String uuid = getHeader(event, "Unique-ID");
            String hangupCause = getHeader(event, "Hangup-Cause");

            log.info("📴 Channel Hangup: uuid={}, cause={}", uuid, hangupCause);
            activeSessions.remove(uuid);

        } catch (Exception e) {
            log.error("Error handling CHANNEL_HANGUP", e);
        }
    }

    /**
     * Start audio capture for a call using uuid_record
     * Records audio to a file that can be processed by the AI pipeline
     */
    private void startAudioCapture(String uuid) {
        try {
            if (freeSwitchClient == null || !freeSwitchClient.canSend()) {
                log.warn("FreeSWITCH client not available, cannot start audio capture");
                return;
            }

            log.info("🔗 Starting audio capture for call {}", uuid);

            // Record both legs of the call as WAV (16kHz mono for AI processing)
            String recordCmd = String.format(
                "uuid_record %s start /tmp/call_%s.wav",
                uuid, uuid
            );
            freeSwitchClient.sendAsyncApiCommand("api", recordCmd);
            log.info("Audio recording started for call {}", uuid);

        } catch (Exception e) {
            log.error("Failed to start audio capture for call {}", uuid, e);
        }
    }

    public void stopAudioBridge(String uuid) {
        try {
            if (freeSwitchClient != null && freeSwitchClient.canSend()) {
                String cmd = String.format("uuid_record %s stop /tmp/call_%s.wav", uuid, uuid);
                freeSwitchClient.sendApiCommand("api", cmd);
                log.info("Audio recording stopped for call {}", uuid);
            }
        } catch (Exception e) {
            log.error("Failed to stop audio bridge for call {}", uuid, e);
        }
    }

    public Map<String, CallSessionInfo> getActiveSessions() {
        return Map.copyOf(activeSessions);
    }

    private String getHeader(EslEvent event, String name) {
        try {
            return event.getEventHeaders().get(name);
        } catch (Exception e) {
            return "unknown";
        }
    }

    public record CallSessionInfo(String uuid, String caller, String callee, String direction) {}
}
