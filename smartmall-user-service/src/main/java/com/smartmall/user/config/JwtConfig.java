package com.smartmall.user.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

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
}
