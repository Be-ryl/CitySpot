package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.service.impl.RabbitPublishResult;
import com.hmdp.service.impl.VoucherOrderRabbitPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Local fault-test surface; the bean does not exist unless explicitly enabled. */
@RestController
@RequestMapping("/internal/fault")
@ConditionalOnProperty(name = "hmdp.fault-injection.enabled", havingValue = "true")
public class FaultInjectionController {

    private final VoucherOrderRabbitPublisher publisher;

    public FaultInjectionController(VoucherOrderRabbitPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/rabbit/poison")
    public Result publishPoison(
            @RequestParam("orderId") Long orderId,
            @RequestParam("userId") Long userId,
            @RequestParam(value = "voucherId", defaultValue = "999999999") Long voucherId
    ) {
        RabbitPublishResult result = publisher.publishFaultMessage(
                new VoucherOrderMessage(orderId, userId, voucherId)
        );
        if (result.getStatus() == RabbitPublishResult.Status.CONFIRMED) {
            return Result.ok(orderId);
        }
        return Result.fail(result.getStatus() + ":" + result.getDetail());
    }
}
