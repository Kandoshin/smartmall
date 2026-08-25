package com.smartmall.user.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(long id) {
        super("用户不存在，id = " + id);
    }
}
