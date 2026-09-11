package com.smartmall.user.controller;

import com.smartmall.common.Result;
import com.smartmall.user.dto.RegisterRequest;
import com.smartmall.user.dto.UserDTO;
import com.smartmall.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.smartmall.user.dto.LoginRequest;
import com.smartmall.user.dto.LoginResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.smartmall.user.dto.LoginResultDTO;
import org.springframework.http.ResponseCookie;
import com.smartmall.user.service.JwtService;
import com.smartmall.user.dto.CsrfTokenDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfToken;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserService userService;
    private final boolean cookieSecure;

    public AuthController(UserService userService,
                          @Value("${smartmall.auth.cookie-secure:true}") boolean cookieSecure) {
        this.userService = userService;
        this.cookieSecure = cookieSecure;
    }

    @GetMapping("/csrf")
    public Result<CsrfTokenDTO> csrf(CsrfToken token) {
        return Result.success(new CsrfTokenDTO(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/register")
    public Result<UserDTO> register(
            @Valid @RequestBody RegisterRequest registerRequest){
        return Result.success(userService.register(registerRequest));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletResponse httpResponse) {
        LoginResultDTO result = userService.login(request);
        ResponseCookie refreshCookie = ResponseCookie
                .from("smartmall_refresh",result.getRefreshToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(JwtService.REFRESH_TOKEN_TTL_SECONDS)
                .build();
        httpResponse.addHeader(
                HttpHeaders.SET_COOKIE,
                refreshCookie.toString()
        );
        return Result.success(result.getResponse());
    }

    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(
            @CookieValue(name = "smartmall_refresh",required = false)
            String refreshToken) {
        return Result.success(userService.refresh(refreshToken));
    }

    @GetMapping("/me")
    public Result<UserDTO> me(@AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        UserDTO user = userService.getUserById(userId);
        return Result.success(user);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse httpResponse) {

        ResponseCookie refreshCookie = ResponseCookie
                .from("smartmall_refresh","")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();

        httpResponse.addHeader(
                HttpHeaders.SET_COOKIE,
                refreshCookie.toString()
        );

        return Result.success(null);
    }

}
