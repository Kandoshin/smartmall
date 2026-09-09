package com.smartmall.user.service;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class JwtService {

    public static final long ACCESS_TOKEN_TTL_SECONDS = 900;
    public static final long REFRESH_TOKEN_TTL_SECONDS = 24 * 60 * 60;

    private final JwtEncoder jwtEncoder;

    public JwtService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    public String createAccessToken(Long userId){
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("smartmall-user-service")
                .subject(userId.toString())
                .audience(List.of("smartmall-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS))
                .build();

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .build();

        JwtEncoderParameters parameters =
                JwtEncoderParameters.from(header, claims);

        Jwt jwt = jwtEncoder.encode(parameters);

        return jwt.getTokenValue();
    }

    public String createRefreshToken(Long userId){
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("smartmall-user-service")
                .subject(userId.toString())
                .audience(List.of("smartmall-refresh"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(REFRESH_TOKEN_TTL_SECONDS))
                .build();

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .build();

        JwtEncoderParameters parameters =
                JwtEncoderParameters.from(header, claims);

        Jwt jwt = jwtEncoder.encode(parameters);

        return jwt.getTokenValue();
    }


}
