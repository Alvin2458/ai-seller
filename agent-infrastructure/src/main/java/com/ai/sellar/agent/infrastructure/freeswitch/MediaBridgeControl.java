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

/**
 * Interface for media bridge operations.
 * Implemented by application layer to decouple infrastructure from application.
 *
 * @author buvidk
 * @since 2026-04-19
 */
public interface MediaBridgeControl {

    /**
     * Start media bridge for a call (audio capture + AI pipeline).
     *
     * @param uuid Call UUID
     * @param caller Caller number
     * @param callee Callee number
     */
    void startBridge(String uuid, String caller, String callee);

    /**
     * Stop media bridge for a call.
     *
     * @param uuid Call UUID
     */
    void stopBridge(String uuid);
}
