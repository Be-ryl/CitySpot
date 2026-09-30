package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private UserMapper userMapper;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void sendCodeStoresSixDigitsUnderPhoneKeyForTwoMinutes() {
        Result result = userService.sendCode("13800138000");

        assertTrue(result.getSuccess());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(RedisConstants.LOGIN_CODE_KEY + "13800138000"),
                codeCaptor.capture(),
                eq(2L),
                eq(TimeUnit.MINUTES)
        );
        assertTrue(codeCaptor.getValue().matches("\\d{6}"));
    }

    @Test
    void loginCannotUseAnotherPhonesCode() {
        when(valueOperations.get(RedisConstants.LOGIN_CODE_KEY + "13900139000")).thenReturn(null);
        LoginFormDTO form = loginForm("13900139000", "123456");

        Result result = userService.login(form);

        assertFalse(result.getSuccess());
        assertEquals("验证码错误", result.getErrorMsg());
        verify(valueOperations).get(RedisConstants.LOGIN_CODE_KEY + "13900139000");
        verifyNoInteractions(hashOperations);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginStoresOnlyUserDtoFieldsAndReturnsToken() {
        String phone = "13800138000";
        String codeKey = RedisConstants.LOGIN_CODE_KEY + phone;
        when(valueOperations.get(codeKey)).thenReturn("123456");
        User user = new User();
        user.setId(7L);
        user.setPhone(phone);
        user.setPassword("secret");
        user.setNickName("redis-user");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(stringRedisTemplate.expire(anyString(), eq(30L), eq(TimeUnit.MINUTES))).thenReturn(true);

        Result result = userService.login(loginForm(phone, "123456"));

        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof String);
        String token = (String) result.getData();
        assertFalse(token.isEmpty());

        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq(RedisConstants.LOGIN_USER_KEY + token), mapCaptor.capture());
        Map<String, Object> storedUser = mapCaptor.getValue();
        assertEquals("7", storedUser.get("id"));
        assertEquals("redis-user", storedUser.get("nickName"));
        assertEquals("", storedUser.get("icon"));
        assertEquals(3, storedUser.size());
        assertFalse(storedUser.containsKey("phone"));
        assertFalse(storedUser.containsKey("password"));
        verify(stringRedisTemplate).expire(
                RedisConstants.LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL,
                TimeUnit.MINUTES
        );
        verify(stringRedisTemplate).delete(codeKey);
    }

    private LoginFormDTO loginForm(String phone, String code) {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(phone);
        form.setCode(code);
        return form;
    }
}
