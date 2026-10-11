package com.hmdp.config;

import com.hmdp.utils.RabbitMqConstants;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@EnableRabbit
@Configuration
@ConditionalOnProperty(name = "hmdp.voucher-order.rabbit.enabled", havingValue = "true")
public class RabbitMqConfig {

    @Bean
    public DirectExchange voucherOrderExchange() {
        return new DirectExchange(RabbitMqConstants.ORDER_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange voucherOrderDeadLetterExchange() {
        return new DirectExchange(RabbitMqConstants.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue voucherOrderQueue() {
        return QueueBuilder.durable(RabbitMqConstants.ORDER_QUEUE)
                .withArgument("x-queue-type", "classic")
                .deadLetterExchange(RabbitMqConstants.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqConstants.DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue voucherOrderDeadLetterQueue() {
        return QueueBuilder.durable(RabbitMqConstants.DEAD_LETTER_QUEUE)
                .withArgument("x-queue-type", "classic")
                .build();
    }

    @Bean
    public Binding voucherOrderBinding(Queue voucherOrderQueue, DirectExchange voucherOrderExchange) {
        return BindingBuilder.bind(voucherOrderQueue)
                .to(voucherOrderExchange)
                .with(RabbitMqConstants.ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding voucherOrderDeadLetterBinding(
            Queue voucherOrderDeadLetterQueue,
            DirectExchange voucherOrderDeadLetterExchange
    ) {
        return BindingBuilder.bind(voucherOrderDeadLetterQueue)
                .to(voucherOrderDeadLetterExchange)
                .with(RabbitMqConstants.DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RetryOperationsInterceptor voucherOrderRetryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(500L, 1.0, 500L)
                // Spring AMQP 2.2 的默认 recoverer 在 MANUAL ACK 下不会主动 reject；
                // rejectManual=true 才能在第 3 次失败后 basicReject(requeue=false) 并进入 DLQ。
                .recoverer((message, cause) -> {
                    throw new AmqpRejectAndDontRequeueException(
                            "订单消费重试3次仍失败，拒绝并路由到DLQ", true, cause
                    );
                })
                .build();
    }

    @Bean(name = "voucherOrderRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory voucherOrderRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RetryOperationsInterceptor voucherOrderRetryInterceptor
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(voucherOrderRetryInterceptor);
        return factory;
    }
}
