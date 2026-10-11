package com.hmdp.service.impl;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.utils.RabbitMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.voucher-order.rabbit.enabled", havingValue = "true")
public class VoucherOrderRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public VoucherOrderRabbitPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${hmdp.voucher-order.rabbit.confirm-timeout:5s}") Duration confirmTimeout
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
        this.rabbitTemplate.setMandatory(true);
    }

    public RabbitPublishResult publish(VoucherOrderMessage order) {
        String correlationId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(correlationId);
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConstants.ORDER_EXCHANGE,
                    RabbitMqConstants.ORDER_ROUTING_KEY,
                    order,
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setCorrelationId(correlationId);
                        return message;
                    },
                    correlationData
            );
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                return RabbitPublishResult.definitelyFailed("publisher-nack:" + confirm.getReason());
            }
            if (correlationData.getReturnedMessage() != null) {
                return RabbitPublishResult.definitelyFailed(
                        "unroutable:" + correlationData.getReturnedMessage()
                );
            }
            return RabbitPublishResult.confirmed();
        } catch (TimeoutException exception) {
            logUnknown(order, correlationId, "confirm-timeout", exception);
            return RabbitPublishResult.unknown("confirm-timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logUnknown(order, correlationId, "confirm-interrupted", exception);
            return RabbitPublishResult.unknown("confirm-interrupted");
        } catch (ExecutionException | AmqpException exception) {
            // 连接异常可能发生在 broker 已接受消息之后，不能据此安全回补库存。
            logUnknown(order, correlationId, "publish-result-unknown", exception);
            return RabbitPublishResult.unknown("publish-result-unknown");
        }
    }

    public RabbitPublishResult publishFaultMessage(VoucherOrderMessage order) {
        return publish(order);
    }

    private void logUnknown(
            VoucherOrderMessage order,
            String correlationId,
            String reason,
            Exception exception
    ) {
        log.error(
                "rabbit_publish_unknown reason={} orderId={} userId={} voucherId={} correlationId={}",
                reason, order.getOrderId(), order.getUserId(), order.getVoucherId(), correlationId, exception
        );
    }
}
