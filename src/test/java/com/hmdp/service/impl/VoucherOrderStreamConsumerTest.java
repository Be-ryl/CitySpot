package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderStreamConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private StreamOperations<String, Object, Object> streamOperations;
    @Mock
    private ObjectProvider<IVoucherOrderService> voucherOrderServiceProvider;
    @Mock
    private IVoucherOrderService voucherOrderService;

    private VoucherOrderStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VoucherOrderStreamConsumer(stringRedisTemplate, voucherOrderServiceProvider);
        when(voucherOrderServiceProvider.getObject()).thenReturn(voucherOrderService);
    }

    @Test
    void successfulDatabaseProcessingIsAcknowledgedAfterServiceReturns() {
        MapRecord<String, Object, Object> record = orderRecord();
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge(
                RedisConstants.STREAM_ORDERS_KEY,
                RedisConstants.STREAM_ORDERS_GROUP,
                record.getId()
        )).thenReturn(1L);

        consumer.processRecord(record);

        ArgumentCaptor<VoucherOrder> orderCaptor = ArgumentCaptor.forClass(VoucherOrder.class);
        InOrder inOrder = inOrder(voucherOrderService, streamOperations);
        inOrder.verify(voucherOrderService).handleVoucherOrder(orderCaptor.capture());
        inOrder.verify(streamOperations).acknowledge(
                RedisConstants.STREAM_ORDERS_KEY,
                RedisConstants.STREAM_ORDERS_GROUP,
                record.getId()
        );
        inOrder.verify(streamOperations).delete(RedisConstants.STREAM_ORDERS_KEY, record.getId());
        assertEquals(30L, orderCaptor.getValue().getId());
        assertEquals(20L, orderCaptor.getValue().getUserId());
        assertEquals(10L, orderCaptor.getValue().getVoucherId());
    }

    @Test
    void databaseFailureDoesNotAcknowledgeMessage() {
        MapRecord<String, Object, Object> record = orderRecord();
        doThrow(new IllegalStateException("database failed"))
                .when(voucherOrderService).handleVoucherOrder(org.mockito.ArgumentMatchers.any(VoucherOrder.class));

        assertThrows(IllegalStateException.class, () -> consumer.processRecord(record));

        verify(streamOperations, never()).acknowledge(
                RedisConstants.STREAM_ORDERS_KEY,
                RedisConstants.STREAM_ORDERS_GROUP,
                record.getId()
        );
    }

    @Test
    void failedAcknowledgeDoesNotDeleteMessage() {
        MapRecord<String, Object, Object> record = orderRecord();
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.acknowledge(
                RedisConstants.STREAM_ORDERS_KEY,
                RedisConstants.STREAM_ORDERS_GROUP,
                record.getId()
        )).thenReturn(0L);

        assertThrows(IllegalStateException.class, () -> consumer.processRecord(record));

        verify(streamOperations, never()).delete(
                RedisConstants.STREAM_ORDERS_KEY,
                record.getId()
        );
    }

    private MapRecord<String, Object, Object> orderRecord() {
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put("id", "30");
        values.put("userId", "20");
        values.put("voucherId", "10");
        return MapRecord.create(RedisConstants.STREAM_ORDERS_KEY, values)
                .withId(RecordId.of("1-0"));
    }
}
