package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginInterceptorsTest {

    @AfterEach
    void cleanThreadLocal() {
        UserHolder.removeUser();
    }

    @Test
    void refreshInterceptorRestoresUserRefreshesTtlAndCleansThreadLocal() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> userMap = new HashMap<>();
        userMap.put("id", "9");
        userMap.put("nickName", "token-user");
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + "valid-token")).thenReturn(userMap);

        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(redisTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", "valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        UserDTO currentUser = UserHolder.getUser();
        assertNotNull(currentUser);
        assertEquals(9L, currentUser.getId());
        assertEquals("token-user", currentUser.getNickName());
        verify(redisTemplate).expire(
                RedisConstants.LOGIN_USER_KEY + "valid-token",
                RedisConstants.LOGIN_USER_TTL,
                TimeUnit.MINUTES
        );

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(UserHolder.getUser());
    }

    @Test
    void missingTokenIsAllowedWithoutRedisLookup() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(redisTemplate);

        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object()
        ));
        assertNull(UserHolder.getUser());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void loginInterceptorReturns401WithoutCurrentUser() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = new LoginInterceptor().preHandle(
                new MockHttpServletRequest(),
                response,
                new Object()
        );

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }
}
