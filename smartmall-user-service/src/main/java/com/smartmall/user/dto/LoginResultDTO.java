package com.smartmall.user.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResultDTO {
    private final LoginResponse response;
    private final String refreshToken;
}
