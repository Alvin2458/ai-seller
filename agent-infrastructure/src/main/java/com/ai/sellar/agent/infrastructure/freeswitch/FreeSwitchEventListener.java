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
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FreeSWITCH Event Listener - Handles call events and triggers AI voice pipeline.
 *
 * Flow:
 * CHANNEL_CREATE  -> Track the call
 * CHANNEL_ANSWER  -> Start media bridge (record audio + feed to AI pipeline)
 * CHANNEL_HANGUP  -> Stop media bridge, cleanup resources
 *
 * @author buvidk
 * @since 2026-04-19
 */
@Component
public class FreeSwitchEventListener {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchEventListener.class);

    private FreeSwitchClient freeSwitchClient;
    private MediaBridgeControl mediaBridge;

    private final Map<String, CallSessionInfo> activeSessions = new ConcurrentHashMap<>();

    public void registerWithClient(FreeSwitchClient client) {
        this.freeSwitchClient = client;
        client.setOnChannelCreate(this::onChannelCreate);
        client.setOnChannelAnswer(this::onChannelAnswer);
        client.setOnChannelHangup(this::onChannelHangup);
        log.info("FreeSwitchEventListener registered with FreeSwitchClient");
    }

    /**
     * Set the MediaBridgeControl (called from config after both beans are initialized).
     */
    public void setMediaBridge(MediaBridgeControl bridge) {
        this.mediaBridge = bridge;
        log.info("MediaBridgeControl set in FreeSwitchEventListener");
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

            // Update session info
            CallSessionInfo info = activeSessions.get(uuid);
            if (info != null) {
                activeSessions.put(uuid, info.withAnswered());
            }

            // Start AI media bridge - this records audio and feeds it to the AI pipeline
            if (mediaBridge != null) {
                // Delay slightly to let the call settle
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        log.info("🤖 Starting AI voice pipeline for call {}", uuid);
                        mediaBridge.startBridge(uuid, caller, callee);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "ai-bridge-start-" + uuid).start();
            } else {
                log.warn("⚠️ MediaBridgeControl not set, cannot start AI pipeline for call {}", uuid);
            }

        } catch (Exception e) {
            log.error("Error handling CHANNEL_ANSWER", e);
        }
    }

    private void onChannelHangup(EslEvent event) {
        try {
            String uuid = getHeader(event, "Unique-ID");
            String hangupCause = getHeader(event, "Hangup-Cause");

            log.info("📴 Channel Hangup: uuid={}, cause={}", uuid, hangupCause);

            // Stop AI media bridge
            if (mediaBridge != null) {
                mediaBridge.stopBridge(uuid);
            }

            activeSessions.remove(uuid);

        } catch (Exception e) {
            log.error("Error handling CHANNEL_HANGUP", e);
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

    public record CallSessionInfo(
        String uuid,
        String caller,
        String callee,
        String direction,
        boolean answered
    ) {
        public CallSessionInfo(String uuid, String caller, String callee, String direction) {
            this(uuid, caller, callee, direction, false);
        }

        public CallSessionInfo withAnswered() {
            return new CallSessionInfo(uuid, caller, callee, direction, true);
        }
    }
}
