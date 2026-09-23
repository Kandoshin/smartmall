package com.smartmall.ai.controller;

import com.smartmall.ai.service.ShoppingGraphService;
import com.smartmall.ai.service.ChatStreamSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "smartmall.ai.api-key=test-key")
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShoppingGraphService shoppingGraphService;

    @Test
    void authenticatedChatStreamsUnifiedEvents() throws Exception {
        doAnswer(invocation -> {
            ChatStreamSink sink = invocation.getArgument(2);
            sink.status("正在查询商品…");
            sink.delta("这里有");
            sink.delta("几款在售键盘");
            return null;
        }).when(shoppingGraphService).runStream(
                eq("推荐一把键盘"), eq("access-token"), any(ChatStreamSink.class));

        MvcResult result = mockMvc.perform(post("/chat")
                        .with(jwt().jwt(jwt -> jwt.subject("7").tokenValue("access-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"推荐一把键盘\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(2_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:status")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")))
                .andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("正在查询商品"));
        assertTrue(body.contains("这里有"));
        assertTrue(body.contains("几款在售键盘"));
    }

    @Test
    void blankMessageIsRejectedBeforeCallingGraph() throws Exception {
        mockMvc.perform(post("/chat")
                        .with(jwt().jwt(jwt -> jwt.subject("7").tokenValue("access-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("消息不能为空"));

        verify(shoppingGraphService, never()).runStream(anyString(), anyString(), any(ChatStreamSink.class));
    }

    @Test
    void missingJwtIsRejected() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void upstreamFailureAfterStreamStartsUsesErrorEvent() throws Exception {
        doThrow(new RuntimeException("AI 服务暂时不可用，请稍后重试"))
                .when(shoppingGraphService).runStream(
                        eq("你好"), eq("access-token"), any(ChatStreamSink.class));

        MvcResult result = mockMvc.perform(post("/chat")
                        .with(jwt().jwt(jwt -> jwt.subject("7").tokenValue("access-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(2_000);
        MvcResult completed = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(containsString("\"code\":503")))
                .andReturn();
        assertTrue(completed.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .contains("AI 服务暂时不可用"));
    }
}
