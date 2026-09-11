package com.smartmall.user.config;

import com.smartmall.user.service.JwtService;
import com.smartmall.user.service.UserService;
import com.smartmall.user.mapper.UserMapper;
import com.smartmall.user.entity.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production Spring beans and cryptography; no database or real/private key files. */
class RefreshJwtDecoderTest {
    private static AnnotationConfigApplicationContext context;
    private static JwtDecoder accessDecoder;
    private static JwtDecoder refreshDecoder;
    private static JwtEncoder encoder;
    private static JwtService jwtService;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPair keys = generateKeys();
        var publicKey = pem("PUBLIC KEY", keys.getPublic().getEncoded());
        var privateKey = pem("PRIVATE KEY", keys.getPrivate().getEncoded());
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-keys", Map.of(
                "smartmall.jwt.public-key", "test-key:public",
                "smartmall.jwt.private-key", "test-key:private")));
        context.addProtocolResolver((location, loader) -> switch (location) {
            case "test-key:public" -> publicKey;
            case "test-key:private" -> privateKey;
            default -> null;
        });
        context.registerBean(UserMapper.class, () -> mock(UserMapper.class));
        context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
        context.register(JwtConfig.class, JwtService.class, UserService.class);
        context.refresh();
        accessDecoder = context.getBean("jwtDecoder", JwtDecoder.class);
        refreshDecoder = context.getBean("refreshJwtDecoder", JwtDecoder.class);
        encoder = context.getBean(JwtEncoder.class);
        jwtService = context.getBean(JwtService.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
    }

    @BeforeEach
    void resetDatabaseMock() {
        reset(context.getBean(UserMapper.class));
    }

    @Test
    void serviceRefreshIssuesValidAccessForVerifiedExistingUser() {
        String token = jwtService.createRefreshToken(42L);
        Instant originalExpiry = refreshDecoder.decode(token).getExpiresAt();
        User user = new User();
        user.setId(42L);
        user.setUsername("alice");
        when(context.getBean(UserMapper.class).selectById(42L)).thenReturn(user);
        var response = context.getBean(UserService.class).refresh(token);
        Jwt access = accessDecoder.decode(response.getAccessToken());
        assertEquals("42", access.getSubject());
        assertEquals(900, response.getExpiresIn());
        assertEquals(900, access.getExpiresAt().getEpochSecond() - access.getIssuedAt().getEpochSecond());
        assertEquals("alice", response.getUser().getUsername());
        assertEquals(42L, response.getUser().getId());
        assertEquals(originalExpiry, refreshDecoder.decode(token).getExpiresAt());
        verify(context.getBean(UserMapper.class)).selectById(42L);
        verifyNoMoreInteractions(context.getBean(UserMapper.class));
    }

    @Test
    void serviceRejectsValidRefreshWhenUserWasDeleted() {
        String token = jwtService.createRefreshToken(42L);
        assertThrows(BadJwtException.class, () -> context.getBean(UserService.class).refresh(token));
        verify(context.getBean(UserMapper.class)).selectById(42L);
        verifyNoMoreInteractions(context.getBean(UserMapper.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void serviceRejectsMissingRefreshCredential(String token) {
        assertThrows(BadJwtException.class, () -> context.getBean(UserService.class).refresh(token));
        verifyNoInteractions(context.getBean(UserMapper.class));
    }

    @Test
    void serviceRejectsInvalidOrAccessTokenBeforeQueryingUser() {
        for (String token : List.of("not-a-jwt", jwtService.createAccessToken(42L))) {
            assertThrows(BadJwtException.class, () -> context.getBean(UserService.class).refresh(token));
        }
        verifyNoInteractions(context.getBean(UserMapper.class));
    }

    @Test
    void userServiceReceivesNamedRefreshDecoderDespiteAccessBeingPrimary() {
        Object injected = ReflectionTestUtils.getField(context.getBean(UserService.class), "refreshJwtDecoder");
        assertSame(refreshDecoder, injected);
        assertNotSame(accessDecoder, injected);
    }

    @Test
    void defaultBeanRemainsAccessDecoderWhenBothDecodersExist() {
        assertEquals(2, context.getBeansOfType(JwtDecoder.class).size());
        assertSame(accessDecoder, context.getBean(JwtDecoder.class));
        assertNotSame(accessDecoder, refreshDecoder);
        assertEquals("42", accessDecoder.decode(jwtService.createAccessToken(42L)).getSubject());
    }

    @Test
    void validRefreshReturnsVerifiedUserAndPreservesOriginalExpiry() {
        String token = jwtService.createRefreshToken(42L);
        Jwt verified = refreshDecoder.decode(token);
        assertEquals("42", verified.getSubject());
        assertEquals("RS256", verified.getHeaders().get("alg"));
        assertEquals(List.of("smartmall-refresh"), verified.getAudience());
        assertEquals("smartmall-user-service", verified.getClaimAsString("iss"));
        assertEquals(86400, verified.getExpiresAt().getEpochSecond() - verified.getIssuedAt().getEpochSecond());
        assertEquals(token, verified.getTokenValue());
        assertEquals(verified.getExpiresAt(), refreshDecoder.decode(token).getExpiresAt());
    }

    @Test
    void accessAndRefreshTokensCannotSwapUses() {
        String access = jwtService.createAccessToken(42L);
        String refresh = jwtService.createRefreshToken(42L);
        assertThrows(JwtException.class, () -> refreshDecoder.decode(access));
        assertThrows(JwtException.class, () -> accessDecoder.decode(refresh));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "future-nbf", "wrong-issuer", "missing-issuer",
            "wrong-audience", "missing-audience", "mixed-audience", "missing-expiry",
            "missing-subject", "empty-subject", "non-numeric-subject", "zero-subject",
            "negative-subject", "overflow-subject", "non-canonical-subject"})
    void rejectsCorrectlySignedButInvalidClaims(String scenario) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuedAt(now.minusSeconds(600));
        if (!scenario.equals("missing-issuer")) {
            claims.issuer(scenario.equals("wrong-issuer") ? "untrusted" : "smartmall-user-service");
        }
        if (!scenario.equals("missing-expiry")) {
            claims.expiresAt(scenario.equals("expired") ? now.minusSeconds(300) : now.plusSeconds(900));
        }
        if (scenario.equals("future-nbf")) claims.notBefore(now.plusSeconds(300));
        if (!scenario.equals("missing-audience")) {
            claims.audience(switch (scenario) {
                case "wrong-audience" -> List.of("another-purpose");
                case "mixed-audience" -> List.of("smartmall-api", "smartmall-refresh");
                default -> List.of("smartmall-refresh");
            });
        }
        if (!scenario.equals("missing-subject")) {
            claims.subject(switch (scenario) {
                case "empty-subject" -> "";
                case "non-numeric-subject" -> "alice";
                case "zero-subject" -> "0";
                case "negative-subject" -> "-42";
                case "overflow-subject" -> "9223372036854775808";
                case "non-canonical-subject" -> "+42";
                default -> "42";
            });
        }
        String token = sign(claims.build(), SignatureAlgorithm.RS256);
        assertThrows(JwtException.class, () -> refreshDecoder.decode(token));
        assertThrows(BadJwtException.class, () -> context.getBean(UserService.class).refresh(token));
        verifyNoInteractions(context.getBean(UserMapper.class));
    }

    @Test
    void rejectsTamperedUserIdWithoutTrustingDecodedPayload() {
        String[] parts = jwtService.createRefreshToken(42L).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String changed = payload.replace("\"sub\":\"42\"", "\"sub\":\"1\"");
        assertNotEquals(payload, changed);
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changed.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThrows(JwtException.class, () -> refreshDecoder.decode(tampered));
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        KeyPair keys = generateKeys();
        JwtEncoder other = new JwtConfig().jwtEncoder(pem("PUBLIC KEY", keys.getPublic().getEncoded()),
                pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        String token = new JwtService(other).createRefreshToken(42L);
        assertThrows(JwtException.class, () -> refreshDecoder.decode(token));
    }

    @Test
    void rejectsOtherAlgorithmEvenWhenSignedWithTrustedKey() {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("smartmall-user-service").subject("42")
                .audience(List.of("smartmall-refresh")).issuedAt(now).expiresAt(now.plusSeconds(900)).build();
        String token = sign(claims, SignatureAlgorithm.RS512);
        assertThrows(JwtException.class, () -> refreshDecoder.decode(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-jwt", "eyJhbGciOiJub25lIn0.eyJzdWIiOiI0MiJ9."})
    void rejectsEmptyMalformedOrUnsignedTokens(String token) {
        assertThrows(JwtException.class, () -> refreshDecoder.decode(token));
    }

    private static String sign(JwtClaimsSet claims, SignatureAlgorithm algorithm) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims)).getTokenValue();
    }

    private static KeyPair generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static ByteArrayResource pem(String label, byte[] bytes) {
        return new ByteArrayResource(("-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----\n").getBytes(StandardCharsets.US_ASCII));
    }
}
