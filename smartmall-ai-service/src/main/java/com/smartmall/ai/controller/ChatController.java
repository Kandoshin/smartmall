package com.smartmall.ai.controller;

import com.smartmall.ai.dto.ChatRequest;
import com.smartmall.ai.dto.ChatResponse;
import com.smartmall.ai.service.AiService;
import com.smartmall.common.Result;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final AiService aiService;

    public ChatController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping
    public Result<ChatResponse> chat(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChatRequest request) {
        String answer = aiService.chat(request.message(), jwt.getTokenValue());
        return Result.success(new ChatResponse(answer));
    }

}
