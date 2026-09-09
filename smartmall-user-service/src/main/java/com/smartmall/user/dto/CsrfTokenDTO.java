package com.smartmall.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** CSRF request proof, not an access token or a refresh token. */
@Getter
@AllArgsConstructor
public class CsrfTokenDTO {
    private final String headerName;
    private final String token;
}
