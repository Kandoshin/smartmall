package com.smartmall.user.config;

import com.smartmall.user.controller.AuthController;
import com.smartmall.user.entity.User;
import com.smartmall.user.exception.GlobalExceptionHandler;
import com.smartmall.user.mapper.UserMapper;
import com.smartmall.user.service.UserService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual Controller, Service, RSA decoders and filters; only persistence/password work is mocked. */
@SpringJUnitConfig(RefreshEndpointTest.Config.class)
@WebAppConfiguration
class RefreshEndpointTest {
    @Autowired WebApplicationContext context;
    @Autowired UserMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder accessDecoder;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(mapper);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void validRefreshProducesUsableAccessWithoutReplacingRefreshCookie() throws Exception {
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        when(mapper.selectById(42L)).thenReturn(user);
        var result = mvc.perform(request(token("valid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(42))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andExpect(cookie().doesNotExist("smartmall_refresh"))
                .andReturn();
        var data = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).get("data");
        String access = data.get("accessToken").asText();
        assertEquals("42", accessDecoder.decode(access).getSubject());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(42));
        verify(mapper, times(2)).selectById(42L);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "tampered", "expired", "access", "issuer", "subject"})
    void invalidRefreshIsGeneric401AndNeverQueriesDatabase(String scenario) throws Exception {
        mvc.perform(request(scenario.equals("missing") ? null : token(scenario)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(cookie().doesNotExist("smartmall_refresh"));
        verifyNoInteractions(mapper);
    }

    @Test
    void deletedUserCannotRefresh() throws Exception {
        mvc.perform(request(token("valid")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"))
                .andExpect(cookie().doesNotExist("smartmall_refresh"));
        verify(mapper).selectById(42L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void validRefreshCookieDoesNotBypassCsrf() throws Exception {
        mvc.perform(post("/auth/refresh").cookie(new Cookie("smartmall_refresh", token("valid"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(mapper);
    }

    private MockHttpServletRequestBuilder request(String refresh) throws Exception {
        var csrf = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String masked = JsonMapper.builder().build().readTree(csrf.getContentAsString())
                .get("data").get("token").asText();
        var request = post("/auth/refresh").cookie(csrf.getCookie("smartmall_csrf"))
                .header("X-XSRF-TOKEN", masked);
        if (refresh != null) request.cookie(new Cookie("smartmall_refresh", refresh));
        return request;
    }

    private String token(String scenario) {
        if (scenario.equals("malformed")) return "not-a-jwt";
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(scenario.equals("issuer") ? "wrong" : "smartmall-user-service")
                .subject(scenario.equals("subject") ? "not-an-id" : "42")
                .audience(List.of(scenario.equals("access") ? "smartmall-api" : "smartmall-refresh"))
                .issuedAt(now.minusSeconds(600))
                .expiresAt(scenario.equals("expired") ? now.minusSeconds(300) : now.plusSeconds(3600))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        if (scenario.equals("tampered")) {
            String[] parts = token.split("\\.");
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            signature[0] ^= 1;
            return parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        }
        return token;
    }

    // Reuse the existing ephemeral-key beans, replacing only the mocked business service.
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, SecurityErrorHandler.class, AuthController.class, GlobalExceptionHandler.class})
    static class Config extends AuthSecurityTest.TestConfig {
        @Bean UserMapper userMapper() { return mock(UserMapper.class); }

        @Override
        @Bean UserService userService() {
            try {
                return new UserService(userMapper(), mock(PasswordEncoder.class),
                        jwtService(jwtEncoder()), refreshJwtDecoder());
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot create test refresh service", exception);
            }
        }
    }
}
