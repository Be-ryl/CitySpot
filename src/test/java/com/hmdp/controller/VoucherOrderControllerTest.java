package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoucherOrderControllerTest {

    @Test
    void seckillEndpointDelegatesBusinessLogicToService() {
        IVoucherOrderService voucherOrderService = mock(IVoucherOrderService.class);
        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", voucherOrderService);
        Result expected = Result.ok(123L);
        when(voucherOrderService.seckillVoucher(10L)).thenReturn(expected);

        Result actual = controller.seckillVoucher(10L);

        assertSame(expected, actual);
        verify(voucherOrderService).seckillVoucher(10L);
    }

    @Test
    void rabbitSeckillEndpointDelegatesBusinessLogicToService() {
        IVoucherOrderService voucherOrderService = mock(IVoucherOrderService.class);
        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", voucherOrderService);
        Result expected = Result.ok(456L);
        when(voucherOrderService.seckillVoucherMq(10L)).thenReturn(expected);

        Result actual = controller.seckillVoucherMq(10L);

        assertSame(expected, actual);
        verify(voucherOrderService).seckillVoucherMq(10L);
    }
}
