package com.hmdp.utils;

public final class RabbitMqConstants {

    public static final String ORDER_EXCHANGE = "voucher.order.exchange";
    public static final String ORDER_QUEUE = "voucher.order.queue";
    public static final String ORDER_ROUTING_KEY = "voucher.order.create";
    public static final String DEAD_LETTER_EXCHANGE = "voucher.order.dlx";
    public static final String DEAD_LETTER_QUEUE = "voucher.order.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "voucher.order.dead";

    private RabbitMqConstants() {
    }
}
