package com.smartmall.user.config;

import com.smartmall.user.controller.AuthController;
import com.smartmall.user.dto.LoginRequest;
import com.smartmall.user.dto.LoginResponse;
import com.smartmall.user.dto.LoginResultDTO;
import com.smartmall.user.dto.RegisterRequest;
import com.smartmall.user.dto.UserDTO;
import com.smartmall.user.exception.GlobalExceptionHandler;
import com.smartmall.user.exception.LoginFailedException;
import com.smartmall.user.exception.UserNotFoundException;
import com.smartmall.user.service.JwtService;
import com.smartmall.user.service.UserService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC + security filters + RSA signing/validation; only business/database access is mocked. */
@SpringJUnitConfig(AuthSecurityTest.TestConfig.class)
@WebAppConfiguration
class AuthSecurityTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserService userService;
    @Autowired private JwtService jwtService;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtDecoder jwtDecoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(userService);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void validTokenReturnsSubjectUserIgnoringQueryParameter() throws Exception {
        when(userService.getUserById(42L)).thenReturn(new UserDTO(42L, "alice", null));
        String token = jwtService.createAccessToken(42L);
        Jwt decoded = jwtDecoder.decode(token);
        assertEquals(900, decoded.getExpiresAt().getEpochSecond() - decoded.getIssuedAt().getEpochSecond());

        mvc.perform(get("/auth/me").param("userId", "99")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.pswd").doesNotExist());
        verify(userService).getUserById(42L);
        verifyNoMoreInteractions(userService);
    }

    @Test
    void refreshTokenHasValidSignatureDistinctAudienceAndOneDayLifetime() throws Exception {
        JwtDecoder verifier = context.getBean(TestConfig.class).refreshSignatureVerifier();
        Jwt decoded = verifier.decode(jwtService.createRefreshToken(42L));
        assertEquals("RS256", decoded.getHeaders().get("alg"));
        assertEquals("42", decoded.getSubject());
        assertEquals(List.of("smartmall-refresh"), decoded.getAudience());
        assertEquals(86400, decoded.getExpiresAt().getEpochSecond() - decoded.getIssuedAt().getEpochSecond());
        verifyNoInteractions(userService);
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        expectUnauthorized(mvc.perform(get("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.createRefreshToken(42L))));
        verifyNoInteractions(userService);
    }

    @Test
    void missingTokenReturnsJson401BeforeBusinessCode() throws Exception {
        expectUnauthorized(mvc.perform(get("/auth/me")));
        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tampered", "expired", "issuer", "audience", "missing-audience", "future", "malformed"})
    void invalidTokenReturnsJson401BeforeBusinessCode(String scenario) throws Exception {
        String token = invalidToken(scenario);
        expectUnauthorized(mvc.perform(get("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
        verifyNoInteractions(userService);
    }

    @Test
    void authenticatedUserStillCannotAccessUsers() throws Exception {
        mvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.createAccessToken(42L)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权访问该资源"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        verifyNoInteractions(userService);
    }

    @Test
    void anonymousUsersRequestReturnsJson401() throws Exception {
        expectUnauthorized(mvc.perform(get("/users")));
        verifyNoInteractions(userService);
    }

    @Test
    void csrfFailureAlsoUsesJson403() throws Exception {
        mvc.perform(post("/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.createAccessToken(42L))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权访问该资源"));
        verifyNoInteractions(userService);
    }

    @Test
    void loginRemainsPublicWithValidCsrfToken() throws Exception {
        LoginResponse response = new LoginResponse();
        response.setAccessToken(jwtService.createAccessToken(42L));
        response.setExpiresIn(JwtService.ACCESS_TOKEN_TTL_SECONDS);
        response.setUser(new UserDTO(42L, "alice", null));
        String refreshToken = jwtService.createRefreshToken(42L);
        when(userService.login(any(LoginRequest.class))).thenReturn(new LoginResultDTO(response, refreshToken));
        var loginResult = mvc.perform(csrfPost("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value(response.getAccessToken()))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.id").value(42))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.response").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(refreshToken))))
                .andExpect(cookie().value("smartmall_refresh", refreshToken))
                .andExpect(cookie().httpOnly("smartmall_refresh", true))
                .andExpect(cookie().secure("smartmall_refresh", true))
                .andExpect(cookie().path("smartmall_refresh", "/api/auth"))
                .andExpect(cookie().maxAge("smartmall_refresh", 86400))
                .andReturn();
        Cookie refreshCookie = loginResult.getResponse().getCookie("smartmall_refresh");
        assertEquals("Strict", refreshCookie.getAttribute("SameSite"));
        assertNull(refreshCookie.getDomain());
        assertNull(loginResult.getRequest().getSession(false));
        verify(userService).login(any(LoginRequest.class));
    }

    @Test
    void registerRemainsPublicWithValidCsrfToken() throws Exception {
        when(userService.register(any(RegisterRequest.class))).thenReturn(new UserDTO(42L, "alice", null));
        mvc.perform(csrfPost("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42));
        verify(userService).register(any(RegisterRequest.class));
    }

    @Test
    void refreshReadsTheCookieIssuedByLoginWithoutBearerOrNewRefreshCookie() throws Exception {
        LoginResponse response = new LoginResponse();
        response.setAccessToken(jwtService.createAccessToken(42L));
        response.setExpiresIn(JwtService.ACCESS_TOKEN_TTL_SECONDS);
        response.setUser(new UserDTO(42L, "alice", null));
        String token = jwtService.createRefreshToken(42L);
        when(userService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResultDTO(response, token));
        when(userService.refresh(token)).thenReturn(response);
        when(userService.refresh(isNull())).thenThrow(new BadJwtException("缺少刷新凭证"));

        Cookie issuedCookie = mvc.perform(csrfPost("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("smartmall_refresh");
        mvc.perform(csrfPost("/auth/refresh").cookie(issuedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value(response.getAccessToken()))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.id").value(42))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().doesNotExist("smartmall_refresh"));
        verify(userService).refresh(token);
        verify(userService, never()).refresh(isNull());
    }

    @Test
    void missingRefreshCookieReachesServiceAndReturnsGeneric401WithValidCsrf() throws Exception {
        when(userService.refresh(isNull())).thenThrow(new BadJwtException("缺少刷新凭证"));
        mvc.perform(csrfPost("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(cookie().doesNotExist("smartmall_refresh"));
        verify(userService).refresh(isNull());
        verifyNoMoreInteractions(userService);
    }

    @Test
    void logoutClearsRefreshCookieWithoutAccessTokenOrBusinessCall() throws Exception {
        String refreshToken = jwtService.createRefreshToken(42L);
        expectLogoutSuccess(mvc.perform(csrfPost("/auth/logout")
                .cookie(new Cookie("smartmall_refresh", refreshToken))));
        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not-a-valid-refresh-token"})
    void logoutRemainsIdempotentWithMissingOrInvalidRefreshCookie(String refreshToken) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            MockHttpServletRequestBuilder request = csrfPost("/auth/logout");
            if (refreshToken != null) {
                request.cookie(new Cookie("smartmall_refresh", refreshToken));
            }
            expectLogoutSuccess(mvc.perform(request));
        }
        verifyNoInteractions(userService);
    }

    @Test
    void blankLoginStillReturnsValidation400() throws Exception {
        mvc.perform(csrfPost("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"test-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(cookie().doesNotExist("smartmall_refresh"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(userService);
    }

    @Test
    void wrongPasswordRetainsBusiness401() throws Exception {
        when(userService.login(any(LoginRequest.class))).thenThrow(new LoginFailedException());
        mvc.perform(csrfPost("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("smartmall_refresh"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(new LoginFailedException().getMessage()));
    }

    @Test
    void missingUserRetainsBusiness404() throws Exception {
        when(userService.getUserById(42L)).thenThrow(new UserNotFoundException(42L));
        mvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.createAccessToken(42L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void csrfBootstrapIsPublicMaskedSecureAndSessionless() throws Exception {
        var result = mvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.headerName").value("X-XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("smartmall_csrf", true))
                .andExpect(cookie().secure("smartmall_csrf", true))
                .andExpect(cookie().path("smartmall_csrf", "/api/auth"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("smartmall_csrf");
        assertEquals("Strict", cookie.getAttribute("SameSite"));
        assertNull(cookie.getDomain());
        assertNull(result.getRequest().getSession(false));
        assertNull(result.getResponse().getCookie("JSESSIONID"));
        var data = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).get("data");
        assertNotEquals(cookie.getValue(), data.get("token").asText());
        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/login", "/auth/register", "/auth/refresh", "/auth/logout"})
    void invalidCsrfStopsAnonymousWritesBeforeBusinessCode(String path) throws Exception {
        CsrfPair csrf = bootstrapCsrf();
        for (MockHttpServletRequestBuilder request : List.of(
                post(path),
                post(path).cookie(csrf.cookie()),
                post(path).header("X-XSRF-TOKEN", csrf.token()),
                post(path).cookie(csrf.cookie()).header("X-XSRF-TOKEN", "forged-token"))) {
            var result = mvc.perform(request.cookie(new Cookie("smartmall_refresh", "existing-refresh-token"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"alice\",\"password\":\"test-password\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.message").value("无权访问该资源"))
                    .andReturn();
            assertNull(result.getResponse().getCookie("smartmall_refresh"));
            assertNull(result.getRequest().getSession(false));
        }
        verifyNoInteractions(userService);
    }

    private record CsrfPair(Cookie cookie, String token) {}

    private CsrfPair bootstrapCsrf() throws Exception {
        var response = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var data = JsonMapper.builder().build().readTree(response.getContentAsString()).get("data");
        return new CsrfPair(response.getCookie("smartmall_csrf"), data.get("token").asText());
    }

    private MockHttpServletRequestBuilder csrfPost(String path) throws Exception {
        CsrfPair csrf = bootstrapCsrf();
        return post(path).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token());
    }

    private void expectLogoutSuccess(ResultActions result) throws Exception {
        var completed = result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(cookie().value("smartmall_refresh", ""))
                .andExpect(cookie().maxAge("smartmall_refresh", 0))
                .andExpect(cookie().path("smartmall_refresh", "/api/auth"))
                .andExpect(cookie().httpOnly("smartmall_refresh", true))
                .andExpect(cookie().secure("smartmall_refresh", true))
                .andReturn();
        Cookie refreshCookie = completed.getResponse().getCookie("smartmall_refresh");
        assertEquals("Strict", refreshCookie.getAttribute("SameSite"));
        assertNull(refreshCookie.getDomain());
        assertNull(completed.getRequest().getSession(false));
        assertNull(completed.getResponse().getCookie("JSESSIONID"));
    }

    private void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("请先登录或重新登录"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private String invalidToken(String scenario) {
        if (scenario.equals("malformed")) {
            return "not-a-jwt";
        }
        if (scenario.equals("tampered")) {
            String[] parts = jwtService.createAccessToken(42L).split("\\.");
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            signature[0] ^= 1;
            return parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        }
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(scenario.equals("issuer") ? "untrusted-issuer" : "smartmall-user-service")
                .subject("42")
                .issuedAt(now.minusSeconds(1200))
                .expiresAt(scenario.equals("expired") ? now.minusSeconds(300) : now.plusSeconds(900));
        if (!scenario.equals("missing-audience")) {
            claims.audience(List.of(scenario.equals("audience") ? "another-api" : "smartmall-api"));
        }
        if (scenario.equals("future")) {
            claims.notBefore(now.plusSeconds(300));
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, SecurityErrorHandler.class, AuthController.class, GlobalExceptionHandler.class})
    static class TestConfig {
        // Ephemeral test keys: no real private-key files or database access.
        private final KeyPair keys = generateKeys();

        @Bean UserService userService() { return mock(UserService.class); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean JwtService jwtService(JwtEncoder encoder) { return new JwtService(encoder); }

        @Bean
        JwtEncoder jwtEncoder() throws Exception {
            return new JwtConfig().jwtEncoder(pem("PUBLIC KEY", keys.getPublic().getEncoded()),
                    pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        }

        @Bean
        @Primary
        JwtDecoder jwtDecoder() throws Exception {
            return new JwtConfig().jwtDecoder(pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        }

        @Bean
        JwtDecoder refreshJwtDecoder() throws Exception {
            return new JwtConfig().refreshJwtDecoder(pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        }

        private static KeyPair generateKeys() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot generate test RSA key pair", exception);
            }
        }

        // Exercise the production refresh decoder with ephemeral test keys.
        JwtDecoder refreshSignatureVerifier() throws Exception {
            return new JwtConfig().refreshJwtDecoder(pem("PUBLIC KEY", keys.getPublic().getEncoded()));
        }

        private static ByteArrayResource pem(String label, byte[] bytes) {
            String content = "-----BEGIN " + label + "-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                    + "\n-----END " + label + "-----\n";
            return new ByteArrayResource(content.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
