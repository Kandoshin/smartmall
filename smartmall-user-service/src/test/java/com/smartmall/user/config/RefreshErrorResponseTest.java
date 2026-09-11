package com.smartmall.user.config;

import com.smartmall.user.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RefreshErrorResponseTest {
    @Test
    void invalidCredentialUsesGeneric401WithoutLeakingDetails() throws Exception {
        MockMvcBuilders.standaloneSetup(new FailureFixture())
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/test/invalid-credential"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(cookie().doesNotExist("smartmall_refresh"));
    }

    // Test-only route, not a production refresh endpoint or a security bypass.
    @RestController
    static class FailureFixture {
        @GetMapping("/test/invalid-credential")
        public void fail() {
            throw new BadJwtException("sensitive token/signature validation details");
        }
    }
}
