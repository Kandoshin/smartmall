package com.smartmall.order.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtDecoderTest {

    @TempDir
    static Path tempDir;

    private static KeyPair signingKey;
    private static KeyPair otherSigningKey;
    private static Path publicKeyFile;

    @BeforeAll
    static void createTemporaryTestKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        otherSigningKey = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(signingKey.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        publicKeyFile = Files.writeString(tempDir.resolve("public.pem"), pem, StandardCharsets.US_ASCII);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "42", "9223372036854775807"})
    void shouldAcceptAccessTokenWithPositiveLongSubject(String subject) throws Exception {
        String token = sign(validClaims().subject(subject).build());

        withDecoder(decoder -> {
            Jwt verified = decoder.decode(token);
            assertEquals(subject, verified.getSubject());
            assertEquals("smartmall-user-service", verified.getClaimAsString("iss"));
            assertThat(verified.getAudience()).contains("smartmall-api");
        });
    }

    @Test
    void shouldRejectTokenSignedByAnotherKey() throws Exception {
        String token = sign(validClaims().build(), JWSAlgorithm.RS256, otherSigningKey);

        assertRejected(token);
    }

    @Test
    void shouldRejectAnUnconfiguredSignatureAlgorithm() throws Exception {
        String token = sign(validClaims().build(), JWSAlgorithm.RS512, signingKey);

        assertRejected(token);
    }

    @Test
    void shouldRejectExpiredTokenOutsideClockSkew() throws Exception {
        Instant now = Instant.now();
        String token = sign(validClaims()
                .issueTime(Date.from(now.minusSeconds(600)))
                .expirationTime(Date.from(now.minusSeconds(120)))
                .build());

        assertRejected(token);
    }

    @Test
    void shouldRejectTokenNotYetValidOutsideClockSkew() throws Exception {
        Instant now = Instant.now();
        String token = sign(validClaims()
                .notBeforeTime(Date.from(now.plusSeconds(300)))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .build());

        assertRejected(token);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"another-issuer"})
    void shouldRejectWrongOrMissingIssuer(String issuer) throws Exception {
        String token = sign(validClaims().issuer(issuer).build());

        assertRejected(token);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"smartmall-refresh", "another-api"})
    void shouldRejectRefreshWrongOrMissingAudience(String audience) throws Exception {
        String token = sign(validClaims().audience(audience).build());

        assertRejected(token);
    }

    @Test
    void shouldRejectTokenWithoutExpiration() throws Exception {
        String token = sign(validClaims().expirationTime(null).build());

        assertRejected(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-jwt", "a.b.c", "eyJhbGciOiJSUzI1NiJ9.e30."})
    void shouldRejectMalformedToken(String token) {
        assertRejected(token);
    }

    // The order decoder requires a canonical positive Long user ID before identity extraction.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "0", "-1", "9223372036854775808", "01", "+1", " 1"})
    void shouldRejectSubjectThatIsNotACanonicalPositiveLong(String subject) throws Exception {
        String token = sign(validClaims().subject(subject).build());

        assertRejected(token);
    }

    private static JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("smartmall-user-service")
                .audience("smartmall-api")
                .subject("42")
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        return sign(claims, JWSAlgorithm.RS256, signingKey);
    }

    private static String sign(JWTClaimsSet claims, JWSAlgorithm algorithm, KeyPair key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static void assertRejected(String token) {
        withDecoder(decoder -> assertThrows(JwtException.class, () -> decoder.decode(token)));
    }

    private static void withDecoder(Consumer<JwtDecoder> assertions) {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtConfig.class, StrictPropertyResolution.class)
                .withPropertyValues("smartmall.jwt.public-key=" + publicKeyFile.toUri())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
                    assertions.accept(context.getBean(JwtDecoder.class));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class StrictPropertyResolution {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
