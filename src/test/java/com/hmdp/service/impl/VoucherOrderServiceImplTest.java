package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    private static final Long VOUCHER_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long ORDER_ID = 30L;

    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private RedisIdWorker redisIdWorker;
    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    private VoucherOrderServiceImpl voucherOrderService;

    @BeforeEach
    void setUp() {
        voucherOrderService = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(voucherOrderService, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(voucherOrderService, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(voucherOrderService, "baseMapper", voucherOrderMapper);

        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanUserHolder() {
        UserHolder.removeUser();
    }

    @Test
    void invalidVoucherIdFailsWithoutDependencies() {
        Result result = voucherOrderService.seckillVoucher(0L);

        assertFalse(result.getSuccess());
        assertEquals("优惠券ID无效", result.getErrorMsg());
        verifyNoInteractions(seckillVoucherService, redisIdWorker, voucherOrderMapper);
    }

    @Test
    void missingSeckillVoucherReturnsClearFailure() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(null);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀优惠券不存在", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper);
    }

    @Test
    void voucherBeforeBeginTimeCannotBePurchased() {
        SeckillVoucher voucher = activeVoucher()
                .setBeginTime(LocalDateTime.now().plusMinutes(10))
                .setEndTime(LocalDateTime.now().plusMinutes(20));
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀尚未开始", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper);
    }

    @Test
    void voucherAfterEndTimeCannotBePurchased() {
        SeckillVoucher voucher = activeVoucher()
                .setBeginTime(LocalDateTime.now().minusMinutes(20))
                .setEndTime(LocalDateTime.now().minusMinutes(10));
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀已经结束", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper);
    }

    @Test
    void zeroStockCannotBePurchased() {
        SeckillVoucher voucher = activeVoucher().setStock(0);
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedConditionalStockUpdateDoesNotCreateOrder() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher());
        when(redisIdWorker.nextId("order")).thenReturn(ORDER_ID);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(false);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validRequestConditionallyDecrementsStockAndCreatesOrder() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher());
        when(redisIdWorker.nextId("order")).thenReturn(ORDER_ID);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(true);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertTrue(result.getSuccess());
        assertEquals(ORDER_ID, result.getData());

        ArgumentCaptor<VoucherOrder> orderCaptor = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(voucherOrderMapper).insert(orderCaptor.capture());
        VoucherOrder order = orderCaptor.getValue();
        assertEquals(ORDER_ID, order.getId());
        assertEquals(USER_ID, order.getUserId());
        assertEquals(VOUCHER_ID, order.getVoucherId());

        ArgumentCaptor<UpdateWrapper<SeckillVoucher>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(seckillVoucherService).update(wrapperCaptor.capture());
        UpdateWrapper<SeckillVoucher> updateWrapper = wrapperCaptor.getValue();
        assertTrue(updateWrapper.getSqlSet().contains("stock = stock - 1"));
        assertTrue(updateWrapper.getCustomSqlSegment().contains("voucher_id"));
        assertTrue(updateWrapper.getCustomSqlSegment().contains("stock"));
        assertTrue(updateWrapper.getCustomSqlSegment().contains(">"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedOrderSaveThrowsFromTransactionalPublicMethod() throws NoSuchMethodException {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher());
        when(redisIdWorker.nextId("order")).thenReturn(ORDER_ID);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(true);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(0);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID)
        );

        assertEquals("订单创建失败", exception.getMessage());
        Method method = VoucherOrderServiceImpl.class.getMethod("seckillVoucher", Long.class);
        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    private SeckillVoucher activeVoucher() {
        return new SeckillVoucher()
                .setVoucherId(VOUCHER_ID)
                .setStock(5)
                .setBeginTime(LocalDateTime.now().minusMinutes(10))
                .setEndTime(LocalDateTime.now().plusMinutes(10));
    }
}
