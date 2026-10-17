package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    static final double NEARBY_RADIUS_METERS = 5000D;

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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId == null || typeId <= 0) {
            return Result.fail("商户类型无效");
        }
        if (current == null || current <= 0) {
            return Result.fail("页码无效");
        }

        // 不传坐标时保持原来的数据库分页查询行为。
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        if (!isValidCoordinate(x, y)) {
            return Result.fail("经纬度无效");
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        Circle searchArea = new Circle(
                new Point(x, y),
                new Distance(NEARBY_RADIUS_METERS)
        );
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
                .newGeoRadiusArgs()
                .includeDistance()
                .sortAscending()
                .limit(end);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().radius(key, searchArea, args);
        if (results == null || results.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> shopIds = new ArrayList<>(SystemConstants.DEFAULT_PAGE_SIZE);
        Map<Long, Double> distanceByShopId = new HashMap<>();
        results.getContent().stream()
                .skip(from)
                .forEach(result -> collectShopDistance(result, shopIds, distanceByShopId));

        Map<Long, Shop> shopById = listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, Function.identity()));
        List<Shop> shops = shopIds.stream()
                .map(shopById::get)
                .filter(shop -> shop != null)
                .peek(shop -> shop.setDistance(distanceByShopId.get(shop.getId())))
                .collect(Collectors.toList());
        return Result.ok(shops);
    }

    private boolean isValidCoordinate(Double x, Double y) {
        return Double.isFinite(x)
                && Double.isFinite(y)
                && x >= -180D
                && x <= 180D
                && y >= -85.05112878D
                && y <= 85.05112878D;
    }

    private void collectShopDistance(
            GeoResult<RedisGeoCommands.GeoLocation<String>> result,
            List<Long> shopIds,
            Map<Long, Double> distanceByShopId
    ) {
        Long shopId = Long.valueOf(result.getContent().getName());
        shopIds.add(shopId);
        Distance distance = result.getDistance();
        if (distance != null) {
            distanceByShopId.put(shopId, distance.getValue());
        }
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
