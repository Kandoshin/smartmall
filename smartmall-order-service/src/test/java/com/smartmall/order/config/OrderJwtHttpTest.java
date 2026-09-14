package com.smartmall.order.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.smartmall.order.controller.OrderController;
import com.smartmall.order.dto.OrderCreateRequest;
import com.smartmall.order.dto.OrderDTO;
import com.smartmall.order.exception.GlobalExceptionHandler;
import com.smartmall.order.exception.OrderNotFoundException;
import com.smartmall.order.service.OrderService;
import jakarta.servlet.Filter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies current-user order listing, creation, and cancellation through the default Bearer chain and controller.
 * No custom test SecurityFilterChain, database, real account, or real key file is involved.
 * Creation receives only item data and passes the verified subject independently to the service.
 * Detail ownership remains separate work.
 */
class OrderJwtHttpTest {

    private static final String CREATE_ORDER_BODY =
            "{\"items\":[{\"productId\":1,\"quantity\":1}]}";
    private static final String LEGACY_USER_ID_BODY =
            "{\"userId\":8,\"items\":[{\"productId\":1,\"quantity\":1}]}";

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
    void cancelUsesVerifiedSubjectAndPathOrderId() throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);

        withHttp((mvc, service) -> {
            when(service.cancelOrder(7L, 91L)).thenReturn(
                    new OrderDTO(91L, new BigDecimal("12.50"), "CANCELLED"));
            mvc.perform(patch("/orders/91/cancel")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(91))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
            verify(service).cancelOrder(7L, 91L);
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void expiredAccessTokenCannotCancelAnOrder() throws Exception {
        String token = sign("smartmall-api", Instant.now().minusSeconds(300), signingKey);
        withHttp((mvc, service) -> {
            mvc.perform(patch("/orders/91/cancel")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
        });
    }

    @Test
    void foreignOrMissingOrderIsHiddenBehindNotFound() throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);

        withHttp((mvc, service) -> {
            when(service.cancelOrder(7L, 91L)).thenThrow(new OrderNotFoundException(91L));
            mvc.perform(patch("/orders/91/cancel")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("订单不存在：91"))
                    .andExpect(jsonPath("$.data").doesNotExist());
            verify(service).cancelOrder(7L, 91L);
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

    @Test
    void anonymousCreateStopsAt403BeforeServiceInMockMvc() {
        // MockMvc does not automatically execute the servlet container's ERROR dispatch.
        withHttp((mvc, service) -> {
            mvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_ORDER_BODY))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
        });
    }

    @Test
    void anonymousCreateErrorDispatchChanges403To401InRealContainer() throws Exception {
        // Isolated real servlet container; no application.yaml, component scan, or data source.
        try (var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(
                EmbeddedDiagnosticConfiguration.class).registerShutdownHook(false).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.config.location=optional:" + tempDir.resolve("unused-config.yaml").toUri(),
                "--smartmall.jwt.public-key=" + publicKeyFile.toUri())) {
            assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
            int port = context.getWebServer().getPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders"))
                            .timeout(Duration.ofSeconds(10))
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .POST(HttpRequest.BodyPublishers.ofString(CREATE_ORDER_BODY)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(context.getBean(DispatchTrace.class).steps).contains(
                    "REQUEST /orders -> 403", "ERROR /error -> 401");
            verifyNoInteractions(context.getBean(OrderService.class));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 8L})
    void itemsOnlyCreateUsesVerifiedSubjectAndIgnoresForgedQueryUserId(long userId) throws Exception {
        long suppliedUserId = userId == 7L ? 8L : 7L;
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey,
                Long.toString(userId));
        long orderId = 7000 + userId;

        withHttp((mvc, service) -> {
            when(service.createOrder(eq(userId), any(OrderCreateRequest.class))).thenReturn(
                    new OrderDTO(orderId, new BigDecimal("12.50"), "PENDING_PAYMENT"));
            mvc.perform(post("/orders")
                            .param("userId", Long.toString(suppliedUserId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_ORDER_BODY)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("操作成功"))
                    .andExpect(jsonPath("$.data.id").value(orderId))
                    .andExpect(jsonPath("$.data.totalAmount").value(12.5))
                    .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));
            verify(service).createOrder(eq(userId), argThat(request -> request.getItems().size() == 1
                    && request.getItems().get(0).getProductId() == 1L
                    && request.getItems().get(0).getQuantity() == 1));
            verify(service, never()).createOrder(eq(suppliedUserId), any(OrderCreateRequest.class));
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void expiredAccessTokenCannotCreateAnOrder() throws Exception {
        expectCreateRejected(sign("smartmall-api", Instant.now().minusSeconds(300), signingKey));
    }

    @Test
    void refreshAudienceTokenCannotCreateAnOrder() throws Exception {
        expectCreateRejected(sign("smartmall-refresh", Instant.now().plusSeconds(300), signingKey));
    }

    @Test
    void tamperedSignatureCannotCreateAnOrder() throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);
        String[] parts = token.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        String tampered = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        expectCreateRejected(tampered);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\":[]}",
            "{\"items\":[{\"productId\":1,\"quantity\":0}]}",
            "{\"items\":[{\"quantity\":1}]}",
            "{\"items\":[{\"productId\":1}]}"
    })
    void invalidItemsAreRejectedBeforeControllerCallsService(String body) throws Exception {
        expectCreateValidationFailure(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {CREATE_ORDER_BODY, LEGACY_USER_ID_BODY})
    void realContainerCreatesForVerifiedSubjectWithItemsOnlyOrIgnoredLegacyUserId(String body) throws Exception {
        // Exercise Boot's actual JSON configuration, not just MockMvc's default message converter.
        try (var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(
                EmbeddedDiagnosticConfiguration.class).registerShutdownHook(false).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.config.location=optional:" + tempDir.resolve("unused-config.yaml").toUri(),
                "--smartmall.jwt.public-key=" + publicKeyFile.toUri())) {
            assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
            OrderService service = context.getBean(OrderService.class);
            when(service.createOrder(eq(7L), any(OrderCreateRequest.class))).thenReturn(
                    new OrderDTO(7007L, new BigDecimal("12.50"), "PENDING_PAYMENT"));
            String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);
            int port = context.getWebServer().getPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders?userId=8"))
                            .timeout(Duration.ofSeconds(10))
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"code\":200", "\"id\":7007", "\"status\":\"PENDING_PAYMENT\"");
            verify(service).createOrder(eq(7L), argThat(request -> request.getItems().size() == 1
                    && request.getItems().get(0).getProductId() == 1L
                    && request.getItems().get(0).getQuantity() == 1));
            verify(service, never()).createOrder(eq(8L), any(OrderCreateRequest.class));
            verifyNoMoreInteractions(service);
        }
    }

    private static void expectCreateRejected(String token) {
        withHttp((mvc, service) -> {
            mvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_ORDER_BODY)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
        });
    }

    private static void expectCreateValidationFailure(String body) throws Exception {
        String token = sign("smartmall-api", Instant.now().plusSeconds(300), signingKey);
        withHttp((mvc, service) -> {
            mvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
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

    static class DispatchTrace {
        final List<String> steps = new CopyOnWriteArrayList<>();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({JwtConfig.class, OrderController.class})
    static class EmbeddedDiagnosticConfiguration {
        @Bean
        OrderService orderService() { return mock(OrderService.class); }

        @Bean
        DispatchTrace dispatchTrace() { return new DispatchTrace(); }

        @Bean
        FilterRegistrationBean<Filter> diagnosticDispatchObserver(DispatchTrace trace) {
            Filter observer = (request, response, chain) -> {
                var httpRequest = (HttpServletRequest) request;
                var httpResponse = (HttpServletResponse) response;
                String dispatch = httpRequest.getDispatcherType() + " " + httpRequest.getRequestURI();
                try { chain.doFilter(request, response); }
                finally { trace.steps.add(dispatch + " -> " + httpResponse.getStatus()); }
            };
            var registration = new FilterRegistrationBean<>(observer);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({OrderController.class, GlobalExceptionHandler.class})
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
