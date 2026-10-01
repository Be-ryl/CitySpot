package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    static final int MAX_LOCK_RETRIES = 5;
    static final long LOCK_RETRY_INTERVAL_MILLIS = 50L;
    static final long MAX_RANDOM_TTL_MINUTES = 5L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("店铺ID无效");
        }

        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1，调用读redis的函数，从redis里查询商铺缓存
        Result cachedResult = readShopFromCache(key);
        if (cachedResult != null) { 
            return cachedResult;
        }
        //2，如果没有命中开始尝试混存重建。
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        for (int attempt = 0; attempt < MAX_LOCK_RETRIES; attempt++) {
           //2.1如果拿到锁了
            if (tryLock(lockKey)) {
                try {
                    // 获取锁后再次检查，避免等待锁期间其他线程已经完成缓存重建。
                    cachedResult = readShopFromCache(key);
                    if (cachedResult != null) {
                        return cachedResult;   
                    }
                    //拿到锁了，而且确实缓存里没有啊——写入数据库
                    return queryDatabaseAndRebuildCache(id, key);
                } finally {
                    unlock(lockKey);
                }
            }
            //2.2如果没拿到锁。先休眠一段时间
            try {
                Thread.sleep(LOCK_RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.fail("请求被中断，请稍后重试");
            }
            //3 然后去redia里看看是不是别人已经帮完成缓存构建了。
            cachedResult = readShopFromCache(key);
            if (cachedResult != null) {
                return cachedResult;
            }
        }
        return Result.fail("系统繁忙，请稍后重试");
    }
    //封装函数，读取缓存里的商铺。
    private Result readShopFromCache(String key) {
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }
        if (shopJson != null) {
            return Result.fail("店铺不存在");
        }
        return null;
    }

    private Result queryDatabaseAndRebuildCache(Long id, String key) {
        Shop shop = getById(id);
        
        //测试 ，重建演唱时间。
        //缓存空值防止缓存穿透：避免别人反复请求不存在的id，导致每次请求都直接打到数据库。
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(
                    key,
                    "",
                    RedisConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES
            );
            return Result.fail("店铺不存在");
        }

        long ttl = RedisConstants.CACHE_SHOP_TTL
                + ThreadLocalRandom.current().nextLong(MAX_RANDOM_TTL_MINUTES + 1);
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(shop),
                ttl,
                TimeUnit.MINUTES
        );
        return Result.ok(shop);
    }
// 加锁&解锁
    private boolean tryLock(String lockKey) {
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(locked);
    }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    @Override
    @Transactional  
    public Result updateShop(Shop shop) {
        if (shop == null || shop.getId() == null) {
            return Result.fail("店铺ID不能为空");
        }

        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺更新失败");
        }
        //如果是分布式就要借助mq异步通知（优化点）
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }  
}
