package com.smartmall.user.config;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import com.smartmall.user.dto.LoginRequest;
import com.smartmall.user.dto.LoginResponse;
import com.smartmall.user.dto.LoginResultDTO;
import com.smartmall.user.dto.UserDTO;
import com.smartmall.user.service.UserService;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(AuthSecurityTest.TestConfig.class)
@WebAppConfiguration
@TestPropertySource(properties = "smartmall.auth.cookie-secure=false")
class AuthLocalCookieTest {
    @Autowired private WebApplicationContext context;

    @Test
    void localLoginSendsRefreshCookieWithoutSecureAndKeepsTokenOutOfJson() throws Exception {
        UserService userService = context.getBean(UserService.class);
        reset(userService);
        LoginResponse response = new LoginResponse();
        response.setAccessToken("test-access-token");
        response.setExpiresIn(900);
        response.setUser(new UserDTO(42L, "alice", null));
        when(userService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResultDTO(response, "test-refresh-token"));
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        var bootstrap = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String csrf = JsonMapper.builder().build().readTree(bootstrap.getContentAsString())
                .get("data").get("token").asText();
        var result = mvc.perform(post("/auth/login")
                        .cookie(bootstrap.getCookie("smartmall_csrf"))
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("smartmall_refresh", "test-refresh-token"))
                .andExpect(cookie().secure("smartmall_refresh", false))
                .andExpect(cookie().httpOnly("smartmall_refresh", true))
                .andExpect(cookie().path("smartmall_refresh", "/api/auth"))
                .andExpect(cookie().maxAge("smartmall_refresh", 86400))
                .andExpect(jsonPath("$.data.accessToken").value("test-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        assertEquals("Strict", result.getResponse().getCookie("smartmall_refresh").getAttribute("SameSite"));
        assertNull(result.getResponse().getCookie("smartmall_refresh").getDomain());
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void explicitLocalOverrideAllowsHttpWithoutDroppingOtherProtections() throws Exception {
        var result = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build()
                .perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().secure("smartmall_csrf", false))
                .andExpect(cookie().httpOnly("smartmall_csrf", true))
                .andExpect(cookie().path("smartmall_csrf", "/api/auth"))
                .andReturn();
        assertEquals("Strict", result.getResponse().getCookie("smartmall_csrf").getAttribute("SameSite"));
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void localLogoutDeletesRefreshCookieWithoutSecureAndKeepsOtherProtections() throws Exception {
        UserService userService = context.getBean(UserService.class);
        reset(userService);
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        var bootstrap = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String csrf = JsonMapper.builder().build().readTree(bootstrap.getContentAsString())
                .get("data").get("token").asText();

        var result = mvc.perform(post("/auth/logout")
                        .cookie(bootstrap.getCookie("smartmall_csrf"),
                                new Cookie("smartmall_refresh", "existing-refresh-token"))
                        .header("X-XSRF-TOKEN", csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(cookie().value("smartmall_refresh", ""))
                .andExpect(cookie().maxAge("smartmall_refresh", 0))
                .andExpect(cookie().secure("smartmall_refresh", false))
                .andExpect(cookie().httpOnly("smartmall_refresh", true))
                .andExpect(cookie().path("smartmall_refresh", "/api/auth"))
                .andReturn();
        Cookie refreshCookie = result.getResponse().getCookie("smartmall_refresh");
        assertEquals("Strict", refreshCookie.getAttribute("SameSite"));
        assertNull(refreshCookie.getDomain());
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getCookie("JSESSIONID"));
        verifyNoInteractions(userService);
    }
}
