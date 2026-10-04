package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Arrays;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ObjectProvider<IVoucherOrderService> voucherOrderServiceProvider;

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券ID无效");
        }

        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }

        Long userId = user.getId();
        // 1. 生成全局唯一订单ID，后续请求线程和异步消费者始终使用同一个ID
        long orderId = redisIdWorker.nextId("order");

        // 2. 执行Lua脚本，原子判断库存和一人一单，并把订单消息写入Stream
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        RedisConstants.SECKILL_STOCK_KEY + voucherId,
                        RedisConstants.SECKILL_ORDER_KEY + voucherId,
                        RedisConstants.STREAM_ORDERS_KEY
                ),
                voucherId.toString(),
                userId.toString(),
                Long.toString(orderId)
        );

        if (result == null) {
            log.error("秒杀Lua脚本返回空值，orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
            return Result.fail("系统繁忙，请稍后重试");
        }
        if (result == 1L) {
            return Result.fail("库存不足");
        }
        if (result == 2L) {
            return Result.fail("不能重复下单");
        }
        if (result != 0L) {
            log.error("秒杀Lua脚本返回未知状态，status={}, orderId={}, userId={}, voucherId={}",
                    result, orderId, userId, voucherId);
            return Result.fail("系统繁忙，请稍后重试");
        }

        // 3. Lua成功后立即返回，订单由后台消费者异步落库
        return Result.ok(orderId);
    }

    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        String lockKey = RedisConstants.LOCK_ORDER_KEY + userId + ":" + voucherId;
        RLock lock = redissonClient.getLock(lockKey);
        if (!lock.tryLock()) {
            throw new IllegalStateException("订单正在处理中，稍后重试");
        }

        try {
            // 4. 通过Spring代理进入事务，保证锁覆盖事务提交完成的整个过程
            voucherOrderServiceProvider.getObject().createVoucherOrder(voucherOrder);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long orderId = voucherOrder.getId();
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 5. 相同Stream消息重复投递时，已有订单是幂等终态，不能再次扣库存
        if (getById(orderId) != null) {
            return;
        }

        int orderCount = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (orderCount > 0) {
            return;
        }

        // 6. MySQL条件更新仍是数据库库存防超卖的最终保护
        boolean stockUpdated = seckillVoucherService.update(
                new UpdateWrapper<SeckillVoucher>()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId)
                        .gt("stock", 0)
        );
        if (!stockUpdated) {
            throw new IllegalStateException("数据库库存不足，订单暂未落库");
        }

        // 7. 保存失败必须抛异常，使库存扣减回滚并让Stream消息留在Pending List
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单创建失败");
        }
    }
}
