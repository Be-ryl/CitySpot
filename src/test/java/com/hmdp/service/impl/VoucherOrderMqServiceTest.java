package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderMqServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RedisIdWorker idWorker;
    @Mock
    private ObjectProvider<VoucherOrderRabbitPublisher> publisherProvider;
    @Mock
    private ObjectProvider<IVoucherOrderService> serviceProvider;
    @Mock
    private VoucherOrderRabbitPublisher publisher;

    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "redisIdWorker", idWorker);
        ReflectionTestUtils.setField(service, "rabbitPublisherProvider", publisherProvider);
        ReflectionTestUtils.setField(service, "voucherOrderServiceProvider", serviceProvider);
        UserDTO user = new UserDTO();
        user.setId(20L);
        UserHolder.saveUser(user);
        when(publisherProvider.getIfAvailable()).thenReturn(publisher);
        when(idWorker.nextId("order")).thenReturn(30L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservationStatusesMapWithoutPublishing() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L, 2L);

        Result outOfStock = service.seckillVoucherMq(10L);
        Result duplicate = service.seckillVoucherMq(10L);

        assertEquals("库存不足", outOfStock.getErrorMsg());
        assertEquals("不能重复下单", duplicate.getErrorMsg());
        verify(publisher, never()).publish(any(VoucherOrderMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedPublishReturnsOrderIdWithoutCompensation() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(0L);
        when(publisher.publish(any(VoucherOrderMessage.class))).thenReturn(RabbitPublishResult.confirmed());

        Result result = service.seckillVoucherMq(10L);

        assertTrue(result.getSuccess());
        assertEquals(30L, result.getData());
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void definitePublishFailureCompensatesReservation() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(0L, 1L);
        when(publisher.publish(any(VoucherOrderMessage.class)))
                .thenReturn(RabbitPublishResult.definitelyFailed("publisher-nack"));

        Result result = service.seckillVoucherMq(10L);

        assertFalse(result.getSuccess());
        assertEquals("消息投递失败，库存预扣已撤销", result.getErrorMsg());
        verify(redis, org.mockito.Mockito.times(2))
                .execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownPublishResultDoesNotCompensate() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(0L);
        when(publisher.publish(any(VoucherOrderMessage.class)))
                .thenReturn(RabbitPublishResult.unknown("confirm-timeout"));

        Result result = service.seckillVoucherMq(10L);

        assertFalse(result.getSuccess());
        assertEquals("消息确认超时，订单状态未知，请勿重复提交", result.getErrorMsg());
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }
}
