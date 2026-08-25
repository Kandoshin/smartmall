package com.smartmall.user.controller;

import com.smartmall.common.PageResult;
import com.smartmall.common.Result;
import com.smartmall.user.dto.UserDTO;
import com.smartmall.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.smartmall.user.dto.UserCreateRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@RestController
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/users")
    public Result<UserDTO> create(
            @Valid @RequestBody UserCreateRequest request){
        return Result.success(userService.createUser(request));
    }

    @GetMapping("/users")
    public Result<PageResult<UserDTO>> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "1")
            @Min(value = 1,message = "页码必须大于等于1")
            int page,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "每页大小必须大于等于1")
            @Max(value = 100, message = "每页大小不能超过100")
            int size) {
        return Result.success(userService.getUsers(username,email,page,size));
    }

    @GetMapping("/users/count")
    public Result<Long> count() {
        return Result.success(userService.countUsers());
    }

    @GetMapping("/users/{id}")
    public Result<UserDTO> getById(@PathVariable long id){
        return Result.success(userService.getUserById(id));
    }

    @DeleteMapping("/users/{id}")
    public Result<Void> deleteById(@PathVariable long id){
        userService.deleteUserById(id);
        return Result.success(null);
    }

    @PutMapping("/users/{id}")
    public Result<Void> updateById(
            @PathVariable long id,
            @Valid @RequestBody UserCreateRequest request){
        userService.updateUserById(id, request);
        return Result.success(null);
    }

}
