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
package com.ai.sellar.agent.presentation.config;

import com.ai.sellar.agent.application.service.CallMediaBridge;
import com.ai.sellar.agent.infrastructure.freeswitch.FreeSwitchClient;
import com.ai.sellar.agent.infrastructure.freeswitch.FreeSwitchEventListener;
import com.ai.sellar.agent.infrastructure.freeswitch.MediaBridgeControl;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * FreeSWITCH Integration Configuration
 * Registers event listeners and connects media bridge after all beans are initialized.
 *
 * @author buvidk
 * @since 2026-04-19
 */
@Configuration
public class FreeSwitchIntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(FreeSwitchIntegrationConfig.class);

    private final FreeSwitchClient freeSwitchClient;
    private final FreeSwitchEventListener eventListener;
    private final MediaBridgeControl mediaBridge;

    public FreeSwitchIntegrationConfig(FreeSwitchClient freeSwitchClient,
                                       FreeSwitchEventListener eventListener,
                                       MediaBridgeControl mediaBridge) {
        this.freeSwitchClient = freeSwitchClient;
        this.eventListener = eventListener;
        this.mediaBridge = mediaBridge;
    }

    @PostConstruct
    public void init() {
        // Connect media bridge to event listener
        eventListener.setMediaBridge(mediaBridge);

        // Register event listeners with FreeSWITCH client
        if (freeSwitchClient.canSend()) {
            eventListener.registerWithClient(freeSwitchClient);
            log.info("✅ FreeSWITCH integration fully initialized (event listeners + media bridge)");
        } else {
            log.warn("⚠️ FreeSWITCH client not connected, integration deferred");
        }
    }
}
