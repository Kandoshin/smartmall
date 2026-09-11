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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;

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
    void refreshValidatesAndLooksUpUserBeforeIssuingOnlyAccessToken() {
        UserMapper mapper = mock(UserMapper.class);
        JwtDecoder decoder = mock(JwtDecoder.class);
        JwtService tokens = mock(JwtService.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        var service = new UserService(mapper, passwords, tokens, decoder);
        when(decoder.decode("refresh")).thenReturn(verifiedRefresh());
        User user = new User();
        user.setId(10L);
        user.setUsername("alice");
        when(mapper.selectById(10L)).thenReturn(user);
        when(tokens.createAccessToken(10L)).thenReturn("new-access");

        LoginResponse response = service.refresh("refresh");

        assertEquals("new-access", response.getAccessToken());
        assertEquals(900, response.getExpiresIn());
        assertEquals(10L, response.getUser().getId());
        assertEquals("alice", response.getUser().getUsername());
        var order = inOrder(decoder, mapper, tokens);
        order.verify(decoder).decode("refresh");
        order.verify(mapper).selectById(10L);
        order.verify(tokens).createAccessToken(10L);
        verifyNoMoreInteractions(decoder, mapper, tokens);
        verifyNoInteractions(passwords);
    }

    @Test
    void missingRefreshNeverQueriesOrSigns() {
        UserMapper mapper = mock(UserMapper.class);
        JwtDecoder decoder = mock(JwtDecoder.class);
        JwtService tokens = mock(JwtService.class);
        var service = new UserService(mapper, mock(PasswordEncoder.class), tokens, decoder);
        for (String token : new String[]{null, "", " ", "\t"}) {
            assertThrows(BadJwtException.class, () -> service.refresh(token));
        }
        verifyNoInteractions(decoder, mapper, tokens);
    }

    @Test
    void rejectedRefreshNeverQueriesOrSigns() {
        UserMapper mapper = mock(UserMapper.class);
        JwtDecoder decoder = mock(JwtDecoder.class);
        JwtService tokens = mock(JwtService.class);
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("internal validation detail"));
        var service = new UserService(mapper, mock(PasswordEncoder.class), tokens, decoder);
        assertThrows(BadJwtException.class, () -> service.refresh("invalid"));
        verifyNoInteractions(mapper, tokens);
    }

    @Test
    void deletedUserNeverReceivesNewTokens() {
        UserMapper mapper = mock(UserMapper.class);
        JwtDecoder decoder = mock(JwtDecoder.class);
        JwtService tokens = mock(JwtService.class);
        when(decoder.decode("refresh")).thenReturn(verifiedRefresh());
        var service = new UserService(mapper, mock(PasswordEncoder.class), tokens, decoder);
        assertThrows(BadJwtException.class, () -> service.refresh("refresh"));
        verify(mapper).selectById(10L);
        verifyNoInteractions(tokens);
    }

    @Test
    void databaseFailureIsNotTreatedAsSuccessfulRefresh() {
        UserMapper mapper = mock(UserMapper.class);
        JwtDecoder decoder = mock(JwtDecoder.class);
        JwtService tokens = mock(JwtService.class);
        when(decoder.decode("refresh")).thenReturn(verifiedRefresh());
        when(mapper.selectById(10L)).thenThrow(new IllegalStateException("database unavailable"));
        var service = new UserService(mapper, mock(PasswordEncoder.class), tokens, decoder);
        assertThrows(IllegalStateException.class, () -> service.refresh("refresh"));
        verifyNoInteractions(tokens);
    }

    // Unit fixture only; production signature/claims validation is covered by RefreshJwtDecoderTest.
    private Jwt verifiedRefresh() {
        return Jwt.withTokenValue("refresh").header("alg", "RS256").subject("10").build();
    }

    @Test
    void shouldIssueTokenForCorrectPassword() {
        UserMapper mapper = mock(UserMapper.class);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        JwtService jwtService = mock(JwtService.class);
        UserService service = new UserService(mapper, encoder, jwtService, mock(JwtDecoder.class));
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
        UserService service = new UserService(mapper, encoder, jwtService, mock(JwtDecoder.class));
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
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class), mock(JwtDecoder.class));

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
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class), mock(JwtDecoder.class));

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
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class), mock(JwtDecoder.class));

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
        UserService userService = new UserService(userMapper, mock(PasswordEncoder.class), mock(JwtService.class), mock(JwtDecoder.class));

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
