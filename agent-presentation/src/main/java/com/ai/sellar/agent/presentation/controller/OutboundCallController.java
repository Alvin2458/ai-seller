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

import com.ai.sellar.agent.application.service.OutboundCallService;
import com.ai.sellar.agent.application.service.OutboundCallService.CallResult;
import com.ai.sellar.agent.application.service.OutboundCallService.CallSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for Outbound Call Management
 * 
 * @author buvidk
 * @since 2026-04-12
 */
@RestController
@RequestMapping("/api/calls")
public class OutboundCallController {

    private static final Logger log = LoggerFactory.getLogger(OutboundCallController.class);

    private final OutboundCallService outboundCallService;

    public OutboundCallController(OutboundCallService outboundCallService) {
        this.outboundCallService = outboundCallService;
    }

    /**
     * Make an outbound call to an extension number
     * 
     * @param request Call request containing extension number and optional caller ID
     * @return Call result with call ID
     */
    @PostMapping("/outbound")
    public CompletableFuture<ResponseEntity<CallResult>> makeOutboundCall(@RequestBody CallRequest request) {
        log.info("REST API: Making outbound call to extension {}", request.extensionNumber());
        
        CompletableFuture<CallResult> result;
        if (request.callerId() != null) {
            result = outboundCallService.makeOutboundCall(
                request.extensionNumber(), 
                request.callerId()
            );
        } else {
            result = outboundCallService.makeOutboundCall(request.extensionNumber());
        }
        
        return result.thenApply(callResult -> {
            if (callResult.status() == OutboundCallService.CallStatus.INITIATED) {
                return ResponseEntity.ok(callResult);
            } else {
                return ResponseEntity.badRequest().body(callResult);
            }
        });
    }

    /**
     * Hangup an active call
     * 
     * @param callId Call ID to hangup
     * @return Hangup result
     */
    @PostMapping("/{callId}/hangup")
    public ResponseEntity<CallResult> hangupCall(@PathVariable String callId) {
        log.info("REST API: Hanging up call {}", callId);
        CallResult result = outboundCallService.hangupCall(callId);
        
        if (result.status() == OutboundCallService.CallStatus.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(result);
    }

    /**
     * Get call status
     * 
     * @param callId Call ID to check
     * @return Call session information
     */
    @GetMapping("/{callId}")
    public ResponseEntity<CallSession> getCallStatus(@PathVariable String callId) {
        log.info("REST API: Getting status for call {}", callId);
        CallSession session = outboundCallService.getCallStatus(callId);
        
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(session);
    }

    /**
     * Get all active calls
     * 
     * @return Map of active calls
     */
    @GetMapping
    public ResponseEntity<Map<String, CallSession>> getActiveCalls() {
        log.info("REST API: Getting all active calls");
        return ResponseEntity.ok(outboundCallService.getActiveCalls());
    }

    /**
     * Call request DTO
     */
    public record CallRequest(
        String extensionNumber,
        String callerId
    ) {}
}
