package com.hmdp.service.impl;

import com.hmdp.dto.VoucherOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class VoucherOrderRabbitPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private VoucherOrderMessage order;

    @BeforeEach
    void setUp() {
        order = new VoucherOrderMessage(1L, 2L, 3L);
    }

    @Test
    void ackWithoutReturnIsConfirmed() {
        completePublish(true, null, false);
        RabbitPublishResult result = publisher().publish(order);
        assertEquals(RabbitPublishResult.Status.CONFIRMED, result.getStatus());
    }

    @Test
    void nackIsDefiniteFailure() {
        completePublish(false, "disk-error", false);
        RabbitPublishResult result = publisher().publish(order);
        assertEquals(RabbitPublishResult.Status.DEFINITELY_FAILED, result.getStatus());
    }

    @Test
    void returnedMessageIsDefiniteFailureEvenWhenBrokerAcksPublish() {
        completePublish(true, null, true);
        RabbitPublishResult result = publisher().publish(order);
        assertEquals(RabbitPublishResult.Status.DEFINITELY_FAILED, result.getStatus());
    }

    @Test
    void confirmTimeoutIsUnknownAndMustNotBeTreatedAsDefiniteFailure() {
        RabbitPublishResult result = publisher().publish(order);
        assertEquals(RabbitPublishResult.Status.UNKNOWN, result.getStatus());
        assertEquals("confirm-timeout", result.getDetail());
    }

    private VoucherOrderRabbitPublisher publisher() {
        return new VoucherOrderRabbitPublisher(rabbitTemplate, Duration.ofMillis(5));
    }

    private void completePublish(boolean ack, String reason, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            if (returned) {
                correlationData.setReturnedMessage(new Message(new byte[0], new MessageProperties()));
            }
            correlationData.getFuture().set(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class)
        );
    }
}
