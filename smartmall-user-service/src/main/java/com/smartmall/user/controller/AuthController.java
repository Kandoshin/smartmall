package com.smartmall.user.controller;

import com.smartmall.common.Result;
import com.smartmall.user.dto.RegisterRequest;
import com.smartmall.user.dto.UserDTO;
import com.smartmall.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<UserDTO> register(
            @Valid @RequestBody RegisterRequest registerRequest){
        return Result.success(userService.register(registerRequest));
    }
}
