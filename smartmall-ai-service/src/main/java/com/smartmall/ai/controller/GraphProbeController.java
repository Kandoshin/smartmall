package com.smartmall.ai.controller;

import com.smartmall.ai.service.GraphProbeService;
import com.smartmall.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/langgraph-probe")
public class GraphProbeController {

    private final GraphProbeService graphProbeService;

    public GraphProbeController(GraphProbeService graphProbeService) {
        this.graphProbeService = graphProbeService;
    }

    @GetMapping
    public Result<String> run() {
        return Result.success(graphProbeService.run());
    }
}
