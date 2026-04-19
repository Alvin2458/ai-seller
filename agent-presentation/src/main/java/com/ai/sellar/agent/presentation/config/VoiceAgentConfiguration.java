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

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.ai.sellar.agent.domain.model.BookingTool;
import com.ai.sellar.agent.domain.model.FlightChangeTool;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Voice Agent Configuration
 *
 * @author buvidk
 * @since 2026-02-03
 */
@Configuration
public class VoiceAgentConfiguration {

    private final ChatModel chatModel;
    private final BookingTool bookingTool;
    private final FlightChangeTool flightChangeTool;

    public VoiceAgentConfiguration(ChatModel chatModel, 
                                 BookingTool bookingTool, 
                                 FlightChangeTool flightChangeTool) {
        this.chatModel = chatModel;
        this.bookingTool = bookingTool;
        this.flightChangeTool = flightChangeTool;
    }

    @Bean
    public ReactAgent voiceReactAgent() throws GraphStateException {
        return ReactAgent.builder()
            .name("voice-assistant")
            .description("""
                你是一个专业的保险公司销售人员。
                
                你的能力：
                1. 保险推荐服务
                2. 保险购买服务
                
                重要输出规则：
                - 只用纯文本，不要用 Markdown、列表符号或表情符号
                - 保持回复简短，最多2-3句话
                - 用自然口语化的中文回复，像电话交流一样，并且需要自然停顿
                """)
            .model(chatModel)
            .saver(new MemorySaver())
//            .tools(bookingTool.toolCallback(), flightChangeTool.toolCallback())
            .build();
    }
}
