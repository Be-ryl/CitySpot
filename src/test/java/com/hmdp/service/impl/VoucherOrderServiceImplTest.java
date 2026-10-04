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
import com.hmdp.utils.RedisConstants;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    private static final Long VOUCHER_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long ORDER_ID = 30L;
    private static final String LOCK_KEY = RedisConstants.LOCK_ORDER_KEY + USER_ID + ":" + VOUCHER_ID;

    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private RedisIdWorker redisIdWorker;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
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
        ReflectionTestUtils.setField(voucherOrderService, "stringRedisTemplate", stringRedisTemplate);
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
    void invalidVoucherIdFailsWithoutGeneratingIdOrCallingLua() {
        Result result = voucherOrderService.seckillVoucher(0L);

        assertFalse(result.getSuccess());
        assertEquals("优惠券ID无效", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, stringRedisTemplate, voucherOrderMapper, redissonClient);
    }

    @Test
    void unauthenticatedUserFailsBeforeGeneratingIdOrCallingLua() {
        UserHolder.removeUser();

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("请先登录", result.getErrorMsg());
        verifyNoInteractions(redisIdWorker, stringRedisTemplate, voucherOrderMapper, redissonClient);
    }

    @Test
    void luaOutOfStockStatusMapsToClearFailure() {
        stubLuaResult(1L);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
    }

    @Test
    void luaDuplicateStatusMapsToClearFailure() {
        stubLuaResult(2L);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("不能重复下单", result.getErrorMsg());
    }

    @Test
    @SuppressWarnings("unchecked")
    void luaSuccessImmediatelyReturnsGeneratedOrderIdWithoutDatabaseOrLock() {
        stubLuaResult(0L);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertTrue(result.getSuccess());
        assertEquals(ORDER_ID, result.getData());
        verifyNoInteractions(voucherOrderMapper, seckillVoucherService, redissonClient, voucherOrderServiceProvider);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                keysCaptor.capture(),
                eq(VOUCHER_ID.toString()),
                eq(USER_ID.toString()),
                eq(ORDER_ID.toString())
        );
        assertEquals(
                java.util.Arrays.asList(
                        RedisConstants.SECKILL_STOCK_KEY + VOUCHER_ID,
                        RedisConstants.SECKILL_ORDER_KEY + VOUCHER_ID,
                        RedisConstants.STREAM_ORDERS_KEY
                ),
                keysCaptor.getValue()
        );
    }

    @Test
    void nullLuaResultReturnsSystemBusy() {
        stubLuaResult(null);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("系统繁忙，请稍后重试", result.getErrorMsg());
    }

    @Test
    void unknownLuaResultReturnsSystemBusy() {
        stubLuaResult(9L);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("系统繁忙，请稍后重试", result.getErrorMsg());
    }

    @Test
    void alreadyPersistedOrderIdIsIdempotentAndDoesNotDecrementStock() {
        VoucherOrder order = order();
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(order);

        voucherOrderService.createVoucherOrder(order);

        verify(voucherOrderMapper, never()).selectCount(any(Wrapper.class));
        verify(seckillVoucherService, never()).update(any(UpdateWrapper.class));
        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void existingUserVoucherOrderIsTerminalAndDoesNotDecrementStock() {
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1);

        voucherOrderService.createVoucherOrder(order());

        verify(seckillVoucherService, never()).update(any(UpdateWrapper.class));
        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedConditionalStockUpdateDoesNotCreateOrderAndThrowsForRetry() {
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(false);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> voucherOrderService.createVoucherOrder(order())
        );

        assertEquals("数据库库存不足，订单暂未落库", exception.getMessage());
        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validMessageUsesOriginalOrderIdAndCreatesOrder() {
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(true);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        VoucherOrder messageOrder = order();
        voucherOrderService.createVoucherOrder(messageOrder);

        ArgumentCaptor<VoucherOrder> orderCaptor = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(voucherOrderMapper).insert(orderCaptor.capture());
        assertEquals(ORDER_ID, orderCaptor.getValue().getId());
        assertEquals(USER_ID, orderCaptor.getValue().getUserId());
        assertEquals(VOUCHER_ID, orderCaptor.getValue().getVoucherId());
        verifyNoInteractions(redisIdWorker);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedOrderSaveThrowsFromPublicTransactionalMethod() throws NoSuchMethodException {
        when(voucherOrderMapper.selectById(ORDER_ID)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0);
        when(seckillVoucherService.update(any(UpdateWrapper.class))).thenReturn(true);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> voucherOrderService.createVoucherOrder(order()));

        Method method = VoucherOrderServiceImpl.class.getMethod("createVoucherOrder", VoucherOrder.class);
        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    @Test
    void asynchronousHandlerCallsTransactionalServiceProxyBeforeUnlocking() {
        VoucherOrder order = order();
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderServiceProvider.getObject()).thenReturn(voucherOrderServiceProxy);

        voucherOrderService.handleVoucherOrder(order);

        InOrder inOrder = inOrder(voucherOrderServiceProxy, lock);
        inOrder.verify(voucherOrderServiceProxy).createVoucherOrder(order);
        inOrder.verify(lock).isHeldByCurrentThread();
        inOrder.verify(lock).unlock();
        verifyNoInteractions(voucherOrderMapper, seckillVoucherService);
    }

    @Test
    void handlerFailureStillReleasesOwnedLockAndPropagatesForPendingRetry() {
        VoucherOrder order = order();
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(voucherOrderServiceProvider.getObject()).thenReturn(voucherOrderServiceProxy);
        doThrow(new IllegalStateException("database failed"))
                .when(voucherOrderServiceProxy).createVoucherOrder(order);

        assertThrows(IllegalStateException.class, () -> voucherOrderService.handleVoucherOrder(order));

        verify(lock).unlock();
    }

    @Test
    void lockFailureDoesNotCallTransactionalMethod() {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> voucherOrderService.handleVoucherOrder(order()));

        verifyNoInteractions(voucherOrderServiceProvider, voucherOrderMapper, seckillVoucherService);
        verify(lock, never()).unlock();
    }

    @SuppressWarnings("unchecked")
    private void stubLuaResult(Long result) {
        when(redisIdWorker.nextId("order")).thenReturn(ORDER_ID);
        when(stringRedisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(result);
    }

    private VoucherOrder order() {
        return new VoucherOrder()
                .setId(ORDER_ID)
                .setUserId(USER_ID)
                .setVoucherId(VOUCHER_ID);
    }

    private void saveCurrentUser(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }
}
