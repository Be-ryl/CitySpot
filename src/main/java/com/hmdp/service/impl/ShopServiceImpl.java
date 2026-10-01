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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("店铺ID无效");
        }

        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }
        if (shopJson != null) {
            return Result.fail("店铺不存在");
        }

        Shop shop = getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(
                    key,
                    "",
                    RedisConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES
            );
            return Result.fail("店铺不存在");
        }

        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(shop),
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        return Result.ok(shop);
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
