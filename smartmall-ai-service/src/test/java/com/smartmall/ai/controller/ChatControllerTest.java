package com.smartmall.ai.controller;

import com.smartmall.ai.exception.AiUpstreamException;
import com.smartmall.ai.service.AiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "smartmall.ai.api-key=test-key")
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiService aiService;

    @Test
    void authenticatedChatReturnsUnifiedResult() throws Exception {
        when(aiService.chat("推荐一把键盘", "access-token"))
                .thenReturn("这里有几款在售键盘");

        mockMvc.perform(post("/chat")
                        .with(jwt().jwt(jwt -> jwt.subject("7").tokenValue("access-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"推荐一把键盘\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("这里有几款在售键盘"));
    }

    @Test
    void blankMessageIsRejectedBeforeCallingAi() throws Exception {
        mockMvc.perform(post("/chat")
                        .with(jwt().jwt(jwt -> jwt.subject("7").tokenValue("access-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("消息不能为空"));

        verify(aiService, never()).chat(anyString(), anyString());
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
    void upstreamFailureReturnsServiceUnavailable() throws Exception {
        when(aiService.chat("你好", "access-token"))
                .thenThrow(new AiUpstreamException("AI 服务暂时不可用，请稍后重试"));

        mockMvc.perform(post("/chat")
                        .with(jwt().jwt(jwt -> jwt.subject("7").tokenValue("access-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
    }
}
