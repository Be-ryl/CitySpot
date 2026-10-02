package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    private static final Long VOUCHER_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long ORDER_ID = 30L;
    private static final String LOCK_KEY = "lock:order:" + USER_ID + ":" + VOUCHER_ID;

    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private RedisIdWorker redisIdWorker;
    @Mock
    private VoucherOrderMapper voucherOrderMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private ObjectProvider<IVoucherOrderService> voucherOrderServiceProvider;
    @Mock
    private IVoucherOrderService voucherOrderServiceProxy;

    private VoucherOrderServiceImpl voucherOrderService;

    @BeforeEach
    void setUp() {
        voucherOrderService = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(voucherOrderService, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(voucherOrderService, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(voucherOrderService, "baseMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(voucherOrderService, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(
                voucherOrderService,
                "voucherOrderServiceProvider",
                voucherOrderServiceProvider
        );
        saveCurrentUser(USER_ID);
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
        verifyNoInteractions(
                seckillVoucherService,
                redisIdWorker,
                voucherOrderMapper,
                redissonClient,
                voucherOrderServiceProvider
        );
    }

    @Test
    void missingSeckillVoucherReturnsClearFailure() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(null);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀优惠券不存在", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper, redissonClient, voucherOrderServiceProvider);
    }

    @Test
    void voucherBeforeBeginTimeCannotBePurchased() {
        SeckillVoucher voucher = activeVoucher(VOUCHER_ID)
                .setBeginTime(LocalDateTime.now().plusMinutes(10))
                .setEndTime(LocalDateTime.now().plusMinutes(20));
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀尚未开始", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper, redissonClient, voucherOrderServiceProvider);
    }

    @Test
    void voucherAfterEndTimeCannotBePurchased() {
        SeckillVoucher voucher = activeVoucher(VOUCHER_ID)
                .setBeginTime(LocalDateTime.now().minusMinutes(20))
                .setEndTime(LocalDateTime.now().minusMinutes(10));
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀已经结束", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper, redissonClient, voucherOrderServiceProvider);
    }

    @Test
    void zeroStockCannotBePurchased() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher(VOUCHER_ID).setStock(0));

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, voucherOrderMapper, redissonClient, voucherOrderServiceProvider);
    }

    @Test
    void failedLockDoesNotQueryOrdersDecrementStockOrCreateOrder() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher(VOUCHER_ID));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("请求正在处理中，请勿重复下单", result.getErrorMsg());
        verifyNoInteractions(voucherOrderServiceProvider, voucherOrderMapper, redisIdWorker);
        verify(seckillVoucherService, never()).update(any(UpdateWrapper.class));
        verify(lock, never()).unlock();
    }

    @Test
    void acquiredLockCallsServiceProxyBeforeUnlocking() {
        Result expected = Result.ok(ORDER_ID);
        stubSuccessfulLockAndProxy(expected);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertEquals(expected, result);
        InOrder inOrder = inOrder(voucherOrderServiceProxy, lock);
        inOrder.verify(voucherOrderServiceProxy).createVoucherOrder(VOUCHER_ID, USER_ID);
        inOrder.verify(lock).isHeldByCurrentThread();
        inOrder.verify(lock).unlock();
        verifyNoInteractions(voucherOrderMapper, redisIdWorker);
    }

    @Test
    void proxyExceptionStillReleasesCurrentThreadsLock() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher(VOUCHER_ID));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(voucherOrderServiceProvider.getObject()).thenReturn(voucherOrderServiceProxy);
        when(voucherOrderServiceProxy.createVoucherOrder(VOUCHER_ID, USER_ID))
                .thenThrow(new IllegalStateException("transaction failed"));
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> voucherOrderService.seckillVoucher(VOUCHER_ID));

        verify(lock).unlock();
    }

    @Test
    void lockIsNotUnlockedWhenCurrentThreadNoLongerOwnsIt() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher(VOUCHER_ID));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(voucherOrderServiceProvider.getObject()).thenReturn(voucherOrderServiceProxy);
        when(voucherOrderServiceProxy.createVoucherOrder(VOUCHER_ID, USER_ID)).thenReturn(Result.ok(ORDER_ID));
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertTrue(result.getSuccess());
        verify(lock, never()).unlock();
    }

    @Test
    void lockKeyIsStableForSamePairAndDifferentForDifferentUserOrVoucher() {
        when(seckillVoucherService.getById(anyLong()))
                .thenAnswer(invocation -> activeVoucher(invocation.getArgument(0)));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderServiceProvider.getObject()).thenReturn(voucherOrderServiceProxy);
        when(voucherOrderServiceProxy.createVoucherOrder(anyLong(), anyLong())).thenReturn(Result.ok(ORDER_ID));

        voucherOrderService.seckillVoucher(VOUCHER_ID);
        voucherOrderService.seckillVoucher(VOUCHER_ID);
        saveCurrentUser(USER_ID + 1);
        voucherOrderService.seckillVoucher(VOUCHER_ID);
        voucherOrderService.seckillVoucher(VOUCHER_ID + 1);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redissonClient, times(4)).getLock(keyCaptor.capture());
        List<String> lockKeys = keyCaptor.getAllValues();
        assertEquals(LOCK_KEY, lockKeys.get(0));
        assertEquals(lockKeys.get(0), lockKeys.get(1));
        assertNotEquals(lockKeys.get(0), lockKeys.get(2));
        assertNotEquals(lockKeys.get(2), lockKeys.get(3));
        assertEquals("lock:order:21:10", lockKeys.get(2));
        assertEquals("lock:order:21:11", lockKeys.get(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void existingOrderReturnsDuplicateFailureWithoutDecrementingStock() {
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1);

        Result result = voucherOrderService.createVoucherOrder(VOUCHER_ID, USER_ID);

        assertFalse(result.getSuccess());
        assertEquals("不能重复下单", result.getErrorMsg());
        verify(seckillVoucherService, never()).update(any(UpdateWrapper.class));
        verifyNoInteractions(redisIdWorker);
        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));

        ArgumentCaptor<Wrapper<VoucherOrder>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(voucherOrderMapper).selectCount(queryCaptor.capture());
        String querySql = queryCaptor.getValue().getCustomSqlSegment();
        assertTrue(querySql.contains("user_id"));
        assertTrue(querySql.contains("voucher_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedConditionalStockUpdateDoesNotCreateOrder() {
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(false);

        Result result = voucherOrderService.createVoucherOrder(VOUCHER_ID, USER_ID);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker);
        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validFirstPurchaseConditionallyDecrementsStockAndCreatesOrder() {
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(true);
        when(redisIdWorker.nextId("order")).thenReturn(ORDER_ID);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        Result result = voucherOrderService.createVoucherOrder(VOUCHER_ID, USER_ID);

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
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(true);
        when(redisIdWorker.nextId("order")).thenReturn(ORDER_ID);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(0);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> voucherOrderService.createVoucherOrder(VOUCHER_ID, USER_ID)
        );

        assertEquals("订单创建失败", exception.getMessage());
        Method method = VoucherOrderServiceImpl.class.getMethod(
                "createVoucherOrder",
                Long.class,
                Long.class
        );
        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    private void stubSuccessfulLockAndProxy(Result proxyResult) {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeVoucher(VOUCHER_ID));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderServiceProvider.getObject()).thenReturn(voucherOrderServiceProxy);
        when(voucherOrderServiceProxy.createVoucherOrder(VOUCHER_ID, USER_ID)).thenReturn(proxyResult);
    }

    private void saveCurrentUser(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    private SeckillVoucher activeVoucher(Long voucherId) {
        return new SeckillVoucher()
                .setVoucherId(voucherId)
                .setStock(5)
                .setBeginTime(LocalDateTime.now().minusMinutes(10))
                .setEndTime(LocalDateTime.now().plusMinutes(10));
    }
}
