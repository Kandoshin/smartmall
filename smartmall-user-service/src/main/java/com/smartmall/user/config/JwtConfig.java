package com.smartmall.user.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;

@Configuration
public class JwtConfig {

    @Bean
    public JwtEncoder jwtEncoder(
            @Value("${smartmall.jwt.public-key}") Resource publicKeyFile,
            @Value("${smartmall.jwt.private-key}") Resource privateKeyFile
    ) throws IOException {

        try(InputStream publicStream = publicKeyFile.getInputStream();
            InputStream privateStream = privateKeyFile.getInputStream()) {

            RSAPublicKey publicKey=
                    RsaKeyConverters.x509().convert(publicStream);

            RSAPrivateKey privateKey =
                    RsaKeyConverters.pkcs8().convert(privateStream);

            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID("smart-rsa-1")
                    .build();

            return new NimbusJwtEncoder(
                    new ImmutableJWKSet<>(new JWKSet(rsaKey))
            );
        }
    }

    @Bean
    @Primary // 普通 Bearer 请求继续使用 Access Token 校验器。
    public JwtDecoder jwtDecoder(
            @Value("${smartmall.jwt.public-key}") Resource publicKeyFile
    ) throws IOException {

        try(InputStream stream = publicKeyFile.getInputStream()) {

            RSAPublicKey publicKey =
                    RsaKeyConverters.x509().convert(stream);

            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey(publicKey)
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();

            JwtClaimValidator<List<String>> audienceValidator =
                    new JwtClaimValidator<>(
                            "aud",
                            audiences -> audiences !=null
                            && audiences.contains("smartmall-api")
                    );

            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(
                            JwtValidators.createDefaultWithIssuer(
                                    "smartmall-user-service"
                            ),
                            audienceValidator
                    )
            );

            return decoder;


        }
    }

    @Bean
    public JwtDecoder refreshJwtDecoder(
            @Value("${smartmall.jwt.public-key}") Resource publicKeyFile
    ) throws IOException {
        try (InputStream stream = publicKeyFile.getInputStream()) {
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(stream);
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey(publicKey)
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();

            // 只接受刷新用途；不能把 Access Token 当成刷新凭证。
            JwtClaimValidator<List<String>> audienceValidator = new JwtClaimValidator<>(
                    "aud", audiences -> List.of("smartmall-refresh").equals(audiences));
            // 时间校验之外，明确要求 exp 存在，拒绝没有到期时间的令牌。
            JwtClaimValidator<Instant> expiryRequired = new JwtClaimValidator<>(
                    "exp", Objects::nonNull);
            JwtClaimValidator<String> subjectValidator = new JwtClaimValidator<>(
                    "sub", JwtConfig::isValidUserId);

            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer("smartmall-user-service"),
                    audienceValidator, expiryRequired, subjectValidator));
            return decoder;
        }
    }

    private static boolean isValidUserId(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            long userId = Long.parseLong(subject);
            return userId > 0 && Long.toString(userId).equals(subject);
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
