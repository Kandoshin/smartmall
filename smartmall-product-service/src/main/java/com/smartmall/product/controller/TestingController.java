package com.smartmall.product.controller;

import com.smartmall.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestingController {
    @GetMapping("/test")
    public Result<String> test(@RequestParam(defaultValue = "b7") String name){
        return Result.success("hello"+name);
    }
}
