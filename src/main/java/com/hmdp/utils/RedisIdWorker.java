package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //开始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int SEQUENCE_BITS = 32;
    private static final long SEQUENCE_MASK = 0xFFFFFFFFL;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //这里的keyPrefix可以理解成业务前缀。不同业务不同自增
    public long nextId(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("业务前缀不能为空");
        }
        //1，生成时间戳
        ZonedDateTime now = ZonedDateTime.now();
        long timestamp = now.toEpochSecond() - BEGIN_TIMESTAMP;
        //2，生成序列号
        //2.1获取当前日期，精确到天。toLocalDate会把带时区的日期转化成知道有日期的：2026‑10‑01；format又转化成了字符串，20261001
        String date = now.toLocalDate().format(DATE_FORMATTER);
        //2.2自增长
        String counterKey = "id:counter:" + keyPrefix + ":" + date;
        Long sequence = stringRedisTemplate.opsForValue().increment(counterKey);
        if (sequence == null) {
            throw new IllegalStateException("Redis订单ID序列生成失败");
        }
            //雪花算法
        return (timestamp << SEQUENCE_BITS) | (sequence & SEQUENCE_MASK);
    }
}
