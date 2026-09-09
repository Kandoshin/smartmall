package com.smartmall.user.service;

import com.smartmall.user.entity.User;
import com.smartmall.user.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.smartmall.user.dto.LoginRequest;
import com.smartmall.user.dto.LoginResponse;
import com.smartmall.user.dto.LoginResultDTO;
import com.smartmall.user.exception.LoginFailedException;

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
    void shouldIssueTokenForCorrectPassword() {
        UserMapper mapper = mock(UserMapper.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        JwtService jwtService = mock(JwtService.class);
        UserService service = new UserService(mapper, encoder, jwtService);
        User user = new User();
        user.setId(10L);
        user.setUsername("alice");
        user.setPasswordHash(encoder.encode("Example123!"));
        when(mapper.selectOne(any())).thenReturn(user);
        when(jwtService.createAccessToken(10L)).thenReturn("test-token");
        when(jwtService.createRefreshToken(10L)).thenReturn("test-refresh-token");
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("Example123!");

        LoginResultDTO loginResult = service.login(request);
        LoginResponse result = loginResult.getResponse();

        assertEquals("test-refresh-token", loginResult.getRefreshToken());
        assertEquals("test-token", result.getAccessToken());
        assertEquals(JwtService.ACCESS_TOKEN_TTL_SECONDS, result.getExpiresIn());
        assertEquals(10L, result.getUser().getId());
        assertEquals("alice", result.getUser().getUsername());
        verify(jwtService).createAccessToken(10L);
        verify(jwtService).createRefreshToken(10L);
        verifyNoMoreInteractions(jwtService);
        verify(mapper, never()).insert(any(User.class));
    }

    @Test
    void shouldRejectInvalidCredentialsWithoutIssuingToken() {
        UserMapper mapper = mock(UserMapper.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        JwtService jwtService = mock(JwtService.class);
        UserService service = new UserService(mapper, encoder, jwtService);
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("WrongPassword");

        when(mapper.selectOne(any())).thenReturn(null);
        assertThrows(LoginFailedException.class, () -> service.login(request));

        User user = new User();
        user.setId(10L);
        when(mapper.selectOne(any())).thenReturn(user);
        assertThrows(LoginFailedException.class, () -> service.login(request));

        user.setPasswordHash(encoder.encode("Example123!"));
        assertThrows(LoginFailedException.class, () -> service.login(request));
        verifyNoInteractions(jwtService);
        verify(mapper, never()).insert(any(User.class));
    }

    @Test
    void shouldThrowWhenUserDoesNotExist() {
        UserMapper userMapper = mock(UserMapper.class);
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class));

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
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class));

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
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class));

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
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class));

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
