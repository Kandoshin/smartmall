package com.smartmall.order.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtConfigTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtConfig.class, StrictPropertyResolution.class);

    @Test
    void shouldLoadConfiguredPublicKeyAsBean() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey expected = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(expected.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Path publicKeyFile = Files.writeString(tempDir.resolve("public.pem"), pem, StandardCharsets.US_ASCII);

        contextRunner.withPropertyValues("smartmall.jwt.public-key=" + publicKeyFile.toUri())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RSAPublicKey.class);
                    RSAPublicKey actual = context.getBean("jwtPublicKey", RSAPublicKey.class);
                    assertTrue(Arrays.equals(expected.getEncoded(), actual.getEncoded()),
                            "The registered Bean must contain the configured public key");
                });
    }

    @Test
    void shouldFailContextWhenConfiguredFileDoesNotExist() {
        Path missingFile = tempDir.resolve("missing.pem");

        contextRunner.withPropertyValues("smartmall.jwt.public-key=" + missingFile.toUri())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(FileNotFoundException.class);
                });
    }

    @Test
    void shouldFailContextWhenFileIsNotAPemPublicKey() throws Exception {
        Path invalidFile = Files.writeString(tempDir.resolve("invalid.pem"),
                "not a PEM public key", StandardCharsets.US_ASCII);

        contextRunner.withPropertyValues("smartmall.jwt.public-key=" + invalidFile.toUri())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void shouldFailContextWhenPublicKeyPropertyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("smartmall.jwt.public-key");
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
