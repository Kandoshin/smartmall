package com.smartmall.user.service;

import com.smartmall.user.entity.User;
import com.smartmall.user.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.smartmall.user.dto.UserCreateRequest;
import com.smartmall.user.dto.UserDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.smartmall.user.mapper.UserMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartmall.common.PageResult;

import java.util.List;

class UserServiceTest {

    @Test
    void shouldThrowWhenUserDoesNotExist() {
        UserMapper userMapper = mock(UserMapper.class);
        UserService userService = new UserService(userMapper);

       when(userMapper.selectById(999L))
               .thenReturn(null);
        assertThrows(
                UserNotFoundException.class,
                () -> userService.getUserById(999L)
        );
    }

    @Test
    void shouldCreateUserWithGeneratedId(){
        UserMapper userMapper = mock(UserMapper.class);
        UserService userService = new UserService( userMapper);

        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("alice");
        request.setEmail("alice@example.com");

        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(10L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        UserDTO result = userService.createUser(request);

        assertEquals(10L, result.getId());
        assertEquals("alice", result.getUsername());
        assertEquals("alice@example.com", result.getEmail());

        verify(userMapper).insert(any(User.class));
    }

    @Test
    void shouldGetUserById(){
        UserMapper userMapper = mock(UserMapper.class);
        UserService userService = new UserService( userMapper);

        User user = new User();
        user.setId(10L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        when(userMapper.selectById(10L)).thenReturn(user);

        UserDTO result = userService.getUserById(10L);

        assertEquals(10L, result.getId());
        assertEquals("alice", result.getUsername());
        assertEquals("alice@example.com", result.getEmail());

        verify(userMapper).selectById(10L);
    }

    @Test
    void shouldReturnPaginatedUsers() {
        UserMapper userMapper = mock(UserMapper.class);
        UserService userService = new UserService(userMapper);

        User user = new User();
        user.setId(10L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");

        Page<User> resultPage = new Page<>(2, 1);
        resultPage.setTotal(3);
        resultPage.setRecords(List.of(user));

        when(userMapper.selectPage(any(Page.class), any()))
                .thenReturn(resultPage);

        PageResult<UserDTO> result =
                userService.getUsers("alice", null, 2, 1);

        assertEquals(2L, result.getCurrent());
        assertEquals(1L, result.getSize());
        assertEquals(3L, result.getTotal());
        assertEquals(3L, result.getPages());
        assertEquals(1, result.getRecords().size());
        assertEquals("alice", result.getRecords().get(0).getUsername());

        verify(userMapper).selectPage(any(Page.class), any());
    }

}
