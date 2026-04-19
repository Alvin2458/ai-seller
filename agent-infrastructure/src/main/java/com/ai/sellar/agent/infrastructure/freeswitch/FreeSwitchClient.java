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

import org.freeswitch.esl.client.IEslEventListener;
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.inbound.InboundConnectionFailure;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * FreeSWITCH ESL Client Wrapper (Inbound Mode)
 * Uses org.freeswitch.esl.client library for stable ESL connection.
 *
 * @author buvidk
 * @since 2026-04-12
 */
@Component
public class FreeSwitchClient {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchClient.class);

    private Client client;
    private Consumer<EslEvent> onChannelCreate;
    private Consumer<EslEvent> onChannelAnswer;
    private Consumer<EslEvent> onChannelBridge;
    private Consumer<EslEvent> onChannelHangup;
    private Consumer<EslEvent> onCustomEvent;

    @Value("${freeswitch.esl.host:localhost}")
    private String host;

    @Value("${freeswitch.esl.port:8021}")
    private int port;

    @Value("${freeswitch.esl.password:ClueCon}")
    private String password;

    @PostConstruct
    public void init() {
        try {
            log.info("Connecting to FreeSWITCH ESL at {}:{}", host, port);

            client = new Client();
            client.connect(host, port, password, 10);

            // Register event listener
            client.addEventListener(new IEslEventListener() {
                @Override
                public void eventReceived(EslEvent event) {
                    handleEvent(event);
                }

                @Override
                public void backgroundJobResultReceived(EslEvent event) {
                    String jobUuid = event.getEventHeaders().get("Job-UUID");
                    log.info("Background job result: {}", jobUuid);
                }
            });

            // Subscribe to channel events
            client.setEventSubscriptions("plain", "CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_BRIDGE CHANNEL_HANGUP");

            log.info("✅ Successfully connected to FreeSWITCH ESL at {}:{}", host, port);

        } catch (InboundConnectionFailure e) {
            log.error("❌ Failed to connect to FreeSWITCH ESL at {}:{}", host, port, e);
            throw new RuntimeException("Failed to connect to FreeSWITCH", e);
        } catch (Exception e) {
            log.error("❌ Error initializing FreeSWITCH ESL client", e);
            throw new RuntimeException("Failed to initialize FreeSWITCH client", e);
        }
    }

    /**
     * Handle ESL events
     */
    private void handleEvent(EslEvent event) {
        String eventName = event.getEventName();

        try {
            if (eventName != null && eventName.startsWith("CHANNEL_")) {
                switch (eventName) {
                    case "CHANNEL_CREATE":
                        if (onChannelCreate != null) onChannelCreate.accept(event);
                        break;
                    case "CHANNEL_ANSWER":
                        if (onChannelAnswer != null) onChannelAnswer.accept(event);
                        break;
                    case "CHANNEL_BRIDGE":
                        if (onChannelBridge != null) onChannelBridge.accept(event);
                        break;
                    case "CHANNEL_HANGUP":
                        if (onChannelHangup != null) onChannelHangup.accept(event);
                        break;
                    default:
                        log.trace("Other CHANNEL event: {}", eventName);
                        break;
                }
            }
        } catch (Exception e) {
            log.error("Error processing ESL event: {}", eventName, e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (client != null && client.canSend()) {
            log.info("FreeSWITCH ESL connection closed");
        }
    }

    /**
     * Send synchronous API command to FreeSWITCH
     */
    public String sendApiCommand(String command, String argument) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }
        log.debug("Sending sync API command: {} {}", command, argument);
        EslMessage response = client.sendSyncApiCommand(command, argument);
        return response.getBodyLines().toString();
    }

    /**
     * Send asynchronous API command
     */
    public String sendAsyncApiCommand(String command, String argument) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }
        log.debug("Sending async API command: {} {}", command, argument);
        return client.sendAsyncApiCommand(command, argument);
    }

    /**
     * Make an outbound call to an extension number
     */
    public CompletableFuture<String> makeOutboundCallToExtension(String extensionNumber, String callerId) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String originateArg = String.format(
                        "user/%s &park()", extensionNumber
                );
                log.info("📞 Initiating outbound call to extension {}", extensionNumber);
                String jobUuid = sendAsyncApiCommand("originate", originateArg);
                log.info("✅ Call initiated - Job UUID: {}", jobUuid);
                return jobUuid;
            } catch (Exception e) {
                log.error("❌ Failed to make outbound call to extension {}", extensionNumber, e);
                throw new RuntimeException("Failed to make outbound call to extension", e);
            }
        });
    }

    /**
     * Make an outbound call
     */
    public CompletableFuture<String> makeOutboundCall(String destinationNumber, String callerId, String gateway) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String originateArg = String.format(
                        "{origination_caller_id_number=%s,origination_caller_id_name=%s}sofia/gateway/%s/%s &park()",
                        callerId, callerId, gateway, destinationNumber
                );
                log.info("📞 Initiating outbound call to {} via gateway {}", destinationNumber, gateway);
                String jobUuid = sendAsyncApiCommand("originate", originateArg);
                log.info("✅ Call initiated - Job UUID: {}", jobUuid);
                return jobUuid;
            } catch (Exception e) {
                log.error("❌ Failed to make outbound call to {}", destinationNumber, e);
                throw new RuntimeException("Failed to make outbound call", e);
            }
        });
    }

    /**
     * Make an outbound call with audio bridge
     */
    public CompletableFuture<String> makeOutboundCallWithBridge(String destinationNumber, String callerId,
                                                                String gateway, String bridgeUrl) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String originateCmd = String.format(
                        "originate {origination_caller_id_number=%s,origination_caller_id_name=%s,execute_on_answer='external_media port=8040 host=%s'}sofia/gateway/%s/%s &park()",
                        callerId, callerId, bridgeUrl, gateway, destinationNumber
                );
                log.info("Executing originate command with bridge: {}", originateCmd);
                String response = sendAsyncApiCommand("originate", originateCmd);
                log.info("Originate with bridge response (UUID): {}", response);
                return response;
            } catch (Exception e) {
                log.error("Failed to make outbound call with bridge to {}", destinationNumber, e);
                throw new RuntimeException("Failed to make outbound call with bridge", e);
            }
        });
    }

    /**
     * Make an outbound call using SIP trunk
     */
    public CompletableFuture<String> makeOutboundCallUsingTrunk(String destinationNumber, String callerId, String trunkName) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String originateArg = String.format(
                        "{origination_caller_id_number=%s,origination_caller_id_name=%s}sofia/gateway/%s/%s &park()",
                        callerId, callerId, trunkName, destinationNumber
                );
                log.info("📞 Initiating outbound call to {} via SIP trunk {}", destinationNumber, trunkName);
                String jobUuid = sendAsyncApiCommand("originate", originateArg);
                log.info("✅ Call initiated - Job UUID: {}", jobUuid);
                return jobUuid;
            } catch (Exception e) {
                log.error("❌ Failed to make outbound call via SIP trunk to {}", destinationNumber, e);
                throw new RuntimeException("Failed to make outbound call via SIP trunk", e);
            }
        });
    }

    public boolean canSend() {
        return client != null && client.canSend();
    }

    public Client getClient() {
        return client;
    }

    // Setter methods for event handlers
    public void setOnChannelCreate(Consumer<EslEvent> onChannelCreate) {
        this.onChannelCreate = onChannelCreate;
    }

    public void setOnChannelAnswer(Consumer<EslEvent> onChannelAnswer) {
        this.onChannelAnswer = onChannelAnswer;
    }

    public void setOnChannelBridge(Consumer<EslEvent> onChannelBridge) {
        this.onChannelBridge = onChannelBridge;
    }

    public void setOnChannelHangup(Consumer<EslEvent> onChannelHangup) {
        this.onChannelHangup = onChannelHangup;
    }

    public void setOnCustomEvent(Consumer<EslEvent> onCustomEvent) {
        this.onCustomEvent = onCustomEvent;
    }

    /**
     * Make an outbound call with WebSocket audio bridge
     */
    public CompletableFuture<String> makeOutboundCallWithWebSocketBridge(String destinationNumber, String callerId,
                                                                       String gateway, String websocketUrl) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String originateArg = String.format(
                        "{origination_caller_id_number=%s,origination_caller_id_name=%s,execute_on_answer='javascript socket_bridge.js %s'}sofia/gateway/%s/%s &park()",
                        callerId, callerId, destinationNumber, gateway, destinationNumber
                );
                log.info("📞 Initiating outbound call to {} via gateway {} with WebSocket bridge to {}",
                    destinationNumber, gateway, websocketUrl);
                String jobUuid = sendAsyncApiCommand("originate", originateArg);
                log.info("✅ Call initiated with WebSocket bridge - Job UUID: {}", jobUuid);
                return jobUuid;
            } catch (Exception e) {
                log.error("❌ Failed to make outbound call with WebSocket bridge to {}", destinationNumber, e);
                throw new RuntimeException("Failed to make outbound call with WebSocket bridge", e);
            }
        });
    }

    /**
     * Start WebSocket audio bridge for an existing call
     */
    public String startWebSocketBridge(String callUuid, String websocketUrl) {
        if (!canSend()) {
            throw new IllegalStateException("FreeSWITCH client is not connected");
        }

        try {
            String command = String.format("uuid_exec %s javascript socket_bridge.js %s", callUuid, callUuid);
            log.info("Starting WebSocket bridge for call {} to {}", callUuid, websocketUrl);
            return sendApiCommand("api", command);
        } catch (Exception e) {
            log.error("❌ Failed to start WebSocket bridge for call {}", callUuid, e);
            throw new RuntimeException("Failed to start WebSocket bridge", e);
        }
    }
}
