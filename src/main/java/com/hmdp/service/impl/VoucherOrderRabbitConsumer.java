package com.hmdp.service.impl;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RabbitMqConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@ConditionalOnProperty(
        name = {"hmdp.voucher-order.rabbit.enabled", "hmdp.voucher-order.rabbit.consumer-enabled"},
        havingValue = "true"
)
public class VoucherOrderRabbitConsumer {

    private final ObjectProvider<IVoucherOrderService> voucherOrderServiceProvider;
    private final VoucherOrderConsumerDelay consumerDelay;

    public VoucherOrderRabbitConsumer(
            ObjectProvider<IVoucherOrderService> voucherOrderServiceProvider,
            VoucherOrderConsumerDelay consumerDelay
    ) {
        this.voucherOrderServiceProvider = voucherOrderServiceProvider;
        this.consumerDelay = consumerDelay;
    }

    @RabbitListener(
            queues = RabbitMqConstants.ORDER_QUEUE,
            containerFactory = "voucherOrderRabbitListenerContainerFactory"
    )
    public void consume(
            VoucherOrderMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        log.debug("开始处理RabbitMQ订单，orderId={}, redelivered由容器记录", message.getOrderId());
        consumerDelay.beforeProcessing();
        VoucherOrder order = new VoucherOrder()
                .setId(message.getOrderId())
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId());
        voucherOrderServiceProvider.getObject().handleVoucherOrder(order);
        // handleVoucherOrder 返回时事务已经提交，之后才允许 ACK。
        channel.basicAck(deliveryTag, false);
    }
}
