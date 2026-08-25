package com.smartmall.user.service;

import com.smartmall.user.dto.UserCreateRequest;
import com.smartmall.user.entity.User;
import com.smartmall.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import com.smartmall.user.dto.UserDTO;
import java.util.List;
import com.smartmall.user.exception.UserNotFoundException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartmall.common.PageResult;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {

        this.userMapper = userMapper;
    }
    public long countUsers() {
        return userMapper.selectCount(null);
    }


    public UserDTO createUser(UserCreateRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());

        userMapper.insert(user);

        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getEmail()
        );
    }

    public PageResult<UserDTO> getUsers(
            String username,
            String email,
            int page,
            int size) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();

        if (username != null && !username.isBlank()) {
            queryWrapper.eq(User::getUsername, username);
        }
        if (email != null && !email.isBlank()) {
            queryWrapper.eq(User::getEmail, email);
        }

        queryWrapper.orderByAsc(User::getId);
        Page<User> userPage = new Page<>(page, size);
        Page<User> resultPage = userMapper.selectPage(userPage, queryWrapper);
        List<User> users = resultPage.getRecords();

        List<UserDTO> records = users.stream()
                .map(user -> new UserDTO(
                        user.getId(),
                        user.getUsername(),
                        user.getEmail()
                ))
                .toList();

        return new PageResult<>(
                records,
                resultPage.getCurrent(),
                resultPage.getSize(),
                resultPage.getTotal(),
                resultPage.getPages()
        );
    }

    public UserDTO getUserById(long id){
        User user = userMapper.selectById(id);

        if(user == null){
            throw new UserNotFoundException(id);
        }
        return new UserDTO(
                user.getId(),
                user.getUsername(),
                user.getEmail()
        );
    }

    public void deleteUserById(long id){
        int affectedRows = userMapper.deleteById(id);

        if (affectedRows == 0){
            throw new UserNotFoundException(id);
        }
    }

    public void updateUserById(long id, UserCreateRequest request) {
        User user = new User();
        user.setId(id);
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());

        int affectedRows = userMapper.updateById(user);

        if (affectedRows == 0){
            throw new UserNotFoundException(id);
        }
    }

}

