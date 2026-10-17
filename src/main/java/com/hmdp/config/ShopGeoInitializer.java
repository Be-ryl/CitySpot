package com.hmdp.config;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 应用启动时把 MySQL 中的商户坐标同步到 Redis GEO。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "hmdp.shop-geo.initialize-on-startup",
        havingValue = "true",
        matchIfMissing = true
)
public class ShopGeoInitializer implements ApplicationRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Shop> shops = shopService.list();
        Map<Long, List<Shop>> shopsByType = shops.stream()
                .filter(this::hasCoordinate)
                .collect(Collectors.groupingBy(Shop::getTypeId));

        int indexedShopCount = 0;
        for (Map.Entry<Long, List<Shop>> entry : shopsByType.entrySet()) {
            String key = RedisConstants.SHOP_GEO_KEY + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> locations =
                    new ArrayList<>(entry.getValue().size());
            for (Shop shop : entry.getValue()) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }

            stringRedisTemplate.delete(key);
            stringRedisTemplate.opsForGeo().add(key, locations);
            indexedShopCount += locations.size();
        }
        log.info("商户 GEO 索引初始化完成，共写入 {} 个商户", indexedShopCount);
    }

    private boolean hasCoordinate(Shop shop) {
        return shop.getId() != null
                && shop.getTypeId() != null
                && shop.getX() != null
                && shop.getY() != null;
    }
}
