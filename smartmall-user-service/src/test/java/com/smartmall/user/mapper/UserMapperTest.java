package com.smartmall.user.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmall.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    void shouldQueryUsers(){
        List<User> users = userMapper.selectList(
                Wrappers.lambdaQuery(User.class)
        );

        assertNotNull(users);
        System.out.println("查询到用户数量：" + users.size());
    }
}
