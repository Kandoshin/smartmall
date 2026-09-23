package com.smartmall.ai.controller;

import com.smartmall.ai.service.ShoppingGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "smartmall.ai.api-key=test-key")
@AutoConfigureMockMvc
class ShoppingGraphControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShoppingGraphService shoppingGraphService;

    @Test
    void forwardsValidatedJwtTokenToGraph() throws Exception {
        when(shoppingGraphService.run("查看我的订单", "access-token"))
                .thenReturn("订单结果");

        mockMvc.perform(get("/langgraph-probe/product")
                        .param("message", "查看我的订单")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("7").tokenValue("access-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("订单结果"));

        verify(shoppingGraphService).run("查看我的订单", "access-token");
    }

    @Test
    void anonymousProductRequestIsRejectedBeforeCallingGraph() throws Exception {
        mockMvc.perform(get("/langgraph-probe/product")
                        .param("message", "推荐一把键盘"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(shoppingGraphService, never()).run(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsInvalidBearerBeforeCallingGraph() throws Exception {
        mockMvc.perform(get("/langgraph-probe/product")
                        .param("message", "查看我的订单")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(shoppingGraphService, never()).run(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
