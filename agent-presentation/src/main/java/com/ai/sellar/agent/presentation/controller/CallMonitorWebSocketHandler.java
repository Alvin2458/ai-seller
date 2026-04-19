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
package com.ai.sellar.agent.presentation.controller;

import com.ai.sellar.agent.application.service.CallMediaBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.util.Map;

/**
 * WebSocket handler for monitoring live call events (STT, Agent, TTS).
 * <p>
 * Connect to ws://host:port/ws/call-monitor to receive real-time events.
 * Send JSON command to subscribe to a specific call:
 * {"type": "subscribe", "uuid": "call-uuid-here"}
 * Or subscribe to all calls (default):
 * {"type": "subscribe"}
 * </p>
 *
 * @author buvidk
 * @since 2026-04-19
 */
@Component
public class CallMonitorWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CallMonitorWebSocketHandler.class);

    private final CallMediaBridge callMediaBridge;
    private final ObjectMapper objectMapper;

    public CallMonitorWebSocketHandler(CallMediaBridge callMediaBridge, ObjectMapper objectMapper) {
        this.callMediaBridge = callMediaBridge;
        this.objectMapper = objectMapper;
        log.info("CallMonitorWebSocketHandler initialized");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Call monitor WebSocket connected: {}", session.getId());

        // Register listener for all calls by default
        String sessionId = session.getId();
        callMediaBridge.addEventListener(sessionId, null, json -> {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(json));
                    }
                } catch (Exception e) {
                    log.debug("Failed to send event to monitor {}: {}", sessionId, e.getMessage());
                }
            }
        });

        sendJson(session, Map.of(
            "type", "monitor_ready",
            "message", "Connected to call monitor. Send {\"type\":\"subscribe\",\"uuid\":\"xxx\"} to filter by call.",
            "timestamp", System.currentTimeMillis()
        ));
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (!(message instanceof TextMessage textMessage)) return;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> command = objectMapper.readValue(textMessage.getPayload(), Map.class);
            String type = (String) command.get("type");

            if ("subscribe".equals(type)) {
                String uuid = (String) command.get("uuid");
                String sessionId = session.getId();
                // Re-register with optional UUID filter
                callMediaBridge.removeEventListener(sessionId);
                callMediaBridge.addEventListener(sessionId, uuid, json -> {
                    if (session.isOpen()) {
                        try {
                            synchronized (session) {
                                session.sendMessage(new TextMessage(json));
                            }
                        } catch (Exception e) {
                            log.debug("Failed to send event to monitor {}: {}", sessionId, e.getMessage());
                        }
                    }
                });
                sendJson(session, Map.of(
                    "type", "subscribed",
                    "uuid", uuid != null ? uuid : "all",
                    "timestamp", System.currentTimeMillis()
                ));
            }
        } catch (Exception e) {
            log.error("Error handling monitor command", e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Call monitor WebSocket error: {}", session.getId(), exception);
        callMediaBridge.removeEventListener(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Call monitor WebSocket closed: {} ({})", session.getId(), status);
        callMediaBridge.removeEventListener(session.getId());
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
            }
        } catch (Exception e) {
            log.error("Failed to send message to monitor client", e);
        }
    }
}
