package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplTest {

    @Mock
    private VoucherMapper voucherMapper;
    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private VoucherServiceImpl voucherService;

    @BeforeEach
    void setUp() {
        voucherService = new VoucherServiceImpl();
        ReflectionTestUtils.setField(voucherService, "baseMapper", voucherMapper);
        ReflectionTestUtils.setField(voucherService, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(voucherService, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void addingSeckillVoucherInitializesRedisStockAfterDatabaseWrites() {
        Voucher voucher = new Voucher()
                .setId(10L)
                .setShopId(1L)
                .setStock(100)
                .setBeginTime(LocalDateTime.now().minusMinutes(1))
                .setEndTime(LocalDateTime.now().plusHours(1));
        when(voucherMapper.insert(voucher)).thenReturn(1);
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        voucherService.addSeckillVoucher(voucher);

        InOrder inOrder = inOrder(voucherMapper, seckillVoucherService, stringRedisTemplate, valueOperations);
        inOrder.verify(voucherMapper).insert(voucher);
        inOrder.verify(seckillVoucherService).save(any(SeckillVoucher.class));
        inOrder.verify(stringRedisTemplate).opsForValue();
        inOrder.verify(valueOperations).set(RedisConstants.SECKILL_STOCK_KEY + 10L, "100");
    }
}
