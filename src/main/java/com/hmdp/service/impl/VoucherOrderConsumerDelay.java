package com.hmdp.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VoucherOrderConsumerDelay {

    private final long processingDelayMillis;

    public VoucherOrderConsumerDelay(
            @Value("${hmdp.voucher-order.consumer.processing-delay-ms:0}") long processingDelayMillis
    ) {
        this.processingDelayMillis = Math.max(processingDelayMillis, 0L);
    }

    public void beforeProcessing() {
        if (processingDelayMillis == 0L) {
            return;
        }
        try {
            Thread.sleep(processingDelayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("订单消费延迟被中断", exception);
        }
    }
}
