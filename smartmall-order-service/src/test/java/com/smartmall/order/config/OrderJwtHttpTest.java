package com.smartmall.order.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.smartmall.order.controller.OrderController;
import com.smartmall.order.dto.OrderDTO;
import com.smartmall.order.service.OrderService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies current-user order listing through the Boot default Bearer chain and production controller.
 * No custom test SecurityFilterChain, database, real account, or real key file is involved.
 * Other order operations require separate ownership checks; these tests cover only list identity.
 */
class OrderJwtHttpTest {

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

    @Test
    void missingTokenIsRejectedBeforeControllerCallsService() {
        withHttp((mvc, service) -> {
            mvc.perform(get("/orders/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            anyOf(equalTo("Bearer"), startsWith("Bearer "))));
            verifyNoInteractions(service);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 8L})
    void validAccessTokenReturnsOrderDtosForItsOwnSubject(long userId) throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey,
                Long.toString(userId));
        long orderId = 7000 + userId;

        withHttp((mvc, service) -> {
            when(service.getOrdersByUserId(userId)).thenReturn(List.of(
                    new OrderDTO(orderId, new BigDecimal("12.50"), "PENDING_PAYMENT")));
            mvc.perform(get("/orders/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("操作成功"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(orderId))
                    .andExpect(jsonPath("$.data[0].totalAmount").value(12.5))
                    .andExpect(jsonPath("$.data[0].status").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.data[0].userId").doesNotExist());
            verify(service).getOrdersByUserId(userId);
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void authenticatedUserWithoutOrdersReceivesAnEmptyDataArray() throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);

        withHttp((mvc, service) -> {
            when(service.getOrdersByUserId(7L)).thenReturn(List.of());
            mvc.perform(get("/orders/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
            verify(service).getOrdersByUserId(7L);
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void expiredTokenIsRejectedBeforeControllerCallsService() throws Exception {
        expectRejected(sign("smartmall-api", Instant.now().minusSeconds(300), signingKey));
    }

    @Test
    void refreshAudienceIsRejectedBeforeControllerCallsService() throws Exception {
        expectRejected(sign("smartmall-refresh", Instant.now().plusSeconds(300), signingKey));
    }

    @Test
    void wrongSigningKeyIsRejectedBeforeControllerCallsService() throws Exception {
        expectRejected(sign("smartmall-api", Instant.now().plusSeconds(300), otherSigningKey));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "0", "-1", "9223372036854775808", "01", "+1", " 1"})
    void invalidUserIdSubjectIsRejectedBeforeControllerCallsService(String subject) throws Exception {
        expectRejected(sign("smartmall-api", Instant.now().plusSeconds(300), signingKey, subject));
    }

    @Test
    void forgedUserIdQueryCannotOverrideTheVerifiedSubject() throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);

        withHttp((mvc, service) -> {
            when(service.getOrdersByUserId(7L)).thenReturn(List.of(
                    new OrderDTO(7007L, new BigDecimal("12.50"), "PENDING_PAYMENT")));
            mvc.perform(get("/orders/me").param("userId", "8")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(7007));
            verify(service).getOrdersByUserId(7L);
            verify(service, never()).getOrdersByUserId(8L);
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void retiredArbitraryUserListRouteCannotExposeOrders() throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);

        withHttp((mvc, service) -> {
            mvc.perform(get("/orders").param("userId", "8")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.data").doesNotExist());
            verifyNoInteractions(service);
        });
    }

    private static void expectRejected(String token) {
        withHttp((mvc, service) -> {
            mvc.perform(get("/orders/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
        });
    }

    private static String sign(String audience, Instant expiration, KeyPair key) throws Exception {
        return sign(audience, expiration, key, "7");
    }

    private static String sign(String audience, Instant expiration, KeyPair key, String subject) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("smartmall-user-service")
                .audience(audience)
                .subject(subject)
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiration))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static void withHttp(HttpAssertions assertions) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SecurityAutoConfiguration.class,
                        ServletWebSecurityAutoConfiguration.class,
                        OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(JwtConfig.class, MvcTestConfiguration.class)
                .withPropertyValues("smartmall.jwt.public-key=" + publicKeyFile.toUri())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SecurityFilterChain.class);
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                            .build();
                    assertions.check(mvc, context.getBean(OrderService.class));
                });
    }

    @FunctionalInterface
    interface HttpAssertions {
        void check(MockMvc mvc, OrderService service) throws Exception;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(OrderController.class)
    static class MvcTestConfiguration {
        @Bean
        OrderService orderService() {
            return mock(OrderService.class);
        }

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
