package com.smartmall.ai.controller;

import com.smartmall.ai.service.ShoppingGraphService;
import com.smartmall.common.Result;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/langgraph-probe")
public class ShoppingGraphController {

    private final ShoppingGraphService shoppingGraphService;

    public ShoppingGraphController(
            ShoppingGraphService shoppingGraphService) {
        this.shoppingGraphService = shoppingGraphService;
    }

    @GetMapping("/product")
    public Result<String> product(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String message) {
        return Result.success(
                shoppingGraphService.run(message, jwt.getTokenValue())
        );
    }
}
