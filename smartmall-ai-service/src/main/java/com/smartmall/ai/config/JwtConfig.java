package com.smartmall.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;

@Configuration
public class JwtConfig {

    @Bean
    public RSAPublicKey jwtPublicKey(
            @Value("${smartmall.jwt.public-key}") Resource publicKeyFile) throws IOException {
        try (InputStream stream = publicKeyFile.getInputStream()) {
            return RsaKeyConverters.x509().convert(stream);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey jwtPublicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("smartmall-user-service"),
                new JwtAudienceValidator("smartmall-api"),
                new JwtClaimValidator<Instant>("exp", expiresAt -> expiresAt != null),
                new JwtClaimValidator<String>("sub", this::isValidUserId)
        ));
        return decoder;
    }

    private boolean isValidUserId(String subject) {
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
