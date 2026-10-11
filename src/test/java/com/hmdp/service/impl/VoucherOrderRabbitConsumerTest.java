package com.hmdp.service.impl;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderRabbitConsumerTest {

    @Mock
    private ObjectProvider<IVoucherOrderService> provider;
    @Mock
    private IVoucherOrderService service;
    @Mock
    private VoucherOrderConsumerDelay delay;
    @Mock
    private Channel channel;

    private VoucherOrderRabbitConsumer consumer;

    @BeforeEach
    void setUp() {
        when(provider.getObject()).thenReturn(service);
        consumer = new VoucherOrderRabbitConsumer(provider, delay);
    }

    @Test
    void transactionCompletesBeforeManualAck() throws Exception {
        consumer.consume(new VoucherOrderMessage(1L, 2L, 3L), channel, 9L);

        ArgumentCaptor<VoucherOrder> order = ArgumentCaptor.forClass(VoucherOrder.class);
        InOrder sequence = inOrder(delay, service, channel);
        sequence.verify(delay).beforeProcessing();
        sequence.verify(service).handleVoucherOrder(order.capture());
        sequence.verify(channel).basicAck(9L, false);
        assertEquals(1L, order.getValue().getId());
        assertEquals(2L, order.getValue().getUserId());
        assertEquals(3L, order.getValue().getVoucherId());
    }

    @Test
    void processingFailureDoesNotAck() throws Exception {
        doThrow(new IllegalStateException("database failed"))
                .when(service).handleVoucherOrder(any(VoucherOrder.class));

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(new VoucherOrderMessage(1L, 2L, 3L), channel, 9L)
        );
        verify(channel, never()).basicAck(9L, false);
    }
}
