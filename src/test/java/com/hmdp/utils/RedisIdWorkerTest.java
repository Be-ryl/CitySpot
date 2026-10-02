package com.hmdp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdWorkerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisIdWorker redisIdWorker;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        redisIdWorker = new RedisIdWorker(stringRedisTemplate);
    }

    @Test
    void nextIdUsesDatedBusinessCounterAndIncreasingSequence() {
        when(valueOperations.increment(anyString())).thenReturn(1L, 2L);

        long firstId =  redisIdWorker.nextId("order");
        long secondId = redisIdWorker.nextId("order");

        assertTrue(firstId > 0);
        assertTrue(secondId > 0);
        assertNotEquals(firstId, secondId);
        assertEquals(1L, firstId & 0xFFFFFFFFL);
        assertEquals(2L, secondId & 0xFFFFFFFFL);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).increment(keyCaptor.capture());
        List<String> counterKeys = keyCaptor.getAllValues();
        String expectedKey = "id:counter:order:"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals(expectedKey, counterKeys.get(0));
        assertEquals(expectedKey, counterKeys.get(1));
    }
}
