package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopServiceImplTest {

    private static final Long SHOP_ID = 1L;
    private static final String SHOP_KEY = RedisConstants.CACHE_SHOP_KEY + SHOP_ID;
    private static final String LOCK_KEY = RedisConstants.LOCK_SHOP_KEY + SHOP_ID;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private GeoOperations<String, String> geoOperations;
    @Mock
    private ShopMapper shopMapper;

    private ShopServiceImpl shopService;

    @BeforeEach
    void setUp() {
        shopService = new ShopServiceImpl();
        ReflectionTestUtils.setField(shopService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(shopService, "baseMapper", shopMapper);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        clearInvocations(stringRedisTemplate);
    }

    @Test
    void invalidQueryIdFailsWithoutRedisOrDatabaseOperations() {
        Result result = shopService.queryShopById(0L);

        assertFalse(result.getSuccess());
        assertEquals("店铺ID无效", result.getErrorMsg());
        verifyNoInteractions(shopMapper, stringRedisTemplate);
    }

    @Test
    void redisHitReturnsShopWithoutQueryingDatabase() {
        Shop cachedShop = shop();
        when(valueOperations.get(SHOP_KEY)).thenReturn(JSONUtil.toJsonStr(cachedShop));

        Result result = shopService.queryShopById(SHOP_ID);

        assertTrue(result.getSuccess());
        assertEquals(cachedShop, result.getData());
        verifyNoInteractions(shopMapper);
        verify(valueOperations, never()).setIfAbsent(
                anyString(), anyString(), anyLong(), any(TimeUnit.class)
        );
    }

    @Test
    void emptyRedisValueReturnsNotFoundWithoutLockingOrQueryingDatabase() {
        when(valueOperations.get(SHOP_KEY)).thenReturn("");

        Result result = shopService.queryShopById(SHOP_ID);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在", result.getErrorMsg());
        verifyNoInteractions(shopMapper);
        verify(valueOperations, never()).setIfAbsent(
                anyString(), anyString(), anyLong(), any(TimeUnit.class)
        );
    }

    @Test
    void redisMissWithLockQueriesDatabaseCachesShopAndUnlocks() {
        Shop databaseShop = shop();
        when(valueOperations.get(SHOP_KEY)).thenReturn(null, null);
        when(valueOperations.setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        )).thenReturn(true);
        when(shopMapper.selectById(SHOP_ID)).thenReturn(databaseShop);

        Result result = shopService.queryShopById(SHOP_ID);

        assertTrue(result.getSuccess());
        assertEquals(databaseShop, result.getData());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(
                eq(SHOP_KEY),
                jsonCaptor.capture(),
                ttlCaptor.capture(),
                eq(TimeUnit.MINUTES)
        );
        assertEquals(databaseShop, JSONUtil.toBean(jsonCaptor.getValue(), Shop.class));
        assertTrue(ttlCaptor.getValue() >= RedisConstants.CACHE_SHOP_TTL);
        assertTrue(ttlCaptor.getValue()
                <= RedisConstants.CACHE_SHOP_TTL + ShopServiceImpl.MAX_RANDOM_TTL_MINUTES);
        verify(stringRedisTemplate).delete(LOCK_KEY);
    }

    @Test
    void cacheRebuiltBeforeSecondCheckSkipsDatabaseAndUnlocks() {
        Shop rebuiltShop = shop().setName("其他线程重建的店铺");
        when(valueOperations.get(SHOP_KEY)).thenReturn(null, JSONUtil.toJsonStr(rebuiltShop));
        when(valueOperations.setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        )).thenReturn(true);

        Result result = shopService.queryShopById(SHOP_ID);

        assertTrue(result.getSuccess());
        assertEquals(rebuiltShop, result.getData());
        verifyNoInteractions(shopMapper);
        verify(stringRedisTemplate).delete(LOCK_KEY);
    }

    @Test
    void failedFirstLockWaitsAndReadsRebuiltCacheWithoutDatabaseQuery() {
        Shop rebuiltShop = shop().setName("等待期间重建的店铺");
        when(valueOperations.get(SHOP_KEY)).thenReturn(null, JSONUtil.toJsonStr(rebuiltShop));
        when(valueOperations.setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        )).thenReturn(false);

        Result result = shopService.queryShopById(SHOP_ID);

        assertTrue(result.getSuccess());
        assertEquals(rebuiltShop, result.getData());
        verifyNoInteractions(shopMapper);
        verify(stringRedisTemplate, never()).delete(LOCK_KEY);
    }

    @Test
    void missingDatabaseShopCachesEmptyValueWithShortTtlAndUnlocks() {
        when(valueOperations.get(SHOP_KEY)).thenReturn(null, null);
        when(valueOperations.setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        )).thenReturn(true);
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        Result result = shopService.queryShopById(SHOP_ID);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在", result.getErrorMsg());
        verify(valueOperations).set(
                SHOP_KEY,
                "",
                RedisConstants.CACHE_NULL_TTL,
                TimeUnit.MINUTES
        );
        verify(stringRedisTemplate).delete(LOCK_KEY);
    }

    @Test
    void databaseExceptionStillReleasesAcquiredLock() {
        when(valueOperations.get(SHOP_KEY)).thenReturn(null, null);
        when(valueOperations.setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        )).thenReturn(true);
        when(shopMapper.selectById(SHOP_ID)).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> shopService.queryShopById(SHOP_ID));

        verify(stringRedisTemplate).delete(LOCK_KEY);
    }

    @Test
    void retryLimitReturnsBusyWithoutQueryingDatabaseOrUnlocking() {
        when(valueOperations.get(SHOP_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        )).thenReturn(false);

        Result result = shopService.queryShopById(SHOP_ID);

        assertFalse(result.getSuccess());
        assertEquals("系统繁忙，请稍后重试", result.getErrorMsg());
        verify(valueOperations, times(ShopServiceImpl.MAX_LOCK_RETRIES)).setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        );
        verifyNoInteractions(shopMapper);
        verify(stringRedisTemplate, never()).delete(LOCK_KEY);
    }

    @Test
    void interruptedLockWaitRestoresInterruptFlagAndReturnsFailure() {
        when(valueOperations.get(SHOP_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                LOCK_KEY,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        )).thenReturn(false);

        Thread.currentThread().interrupt();
        try {
            Result result = shopService.queryShopById(SHOP_ID);

            assertFalse(result.getSuccess());
            assertEquals("请求被中断，请稍后重试", result.getErrorMsg());
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(shopMapper);
            verify(stringRedisTemplate, never()).delete(LOCK_KEY);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void successfulUpdateDeletesShopCache() {
        Shop shop = shop();
        when(shopMapper.updateById(shop)).thenReturn(1);

        Result result = shopService.updateShop(shop);

        assertTrue(result.getSuccess());
        InOrder inOrder = inOrder(shopMapper, stringRedisTemplate);
        inOrder.verify(shopMapper).updateById(shop);
        inOrder.verify(stringRedisTemplate).delete(SHOP_KEY);
    }

    @Test
    void failedDatabaseUpdateDoesNotDeleteCacheOrReportSuccess() {
        Shop shop = shop();
        when(shopMapper.updateById(shop)).thenReturn(0);

        Result result = shopService.updateShop(shop);

        assertFalse(result.getSuccess());
        assertEquals("店铺更新失败", result.getErrorMsg());
        verify(shopMapper).updateById(shop);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void updateWithoutIdFailsWithoutDatabaseOrRedisOperations() {
        Shop shop = new Shop().setName("无ID店铺");

        Result result = shopService.updateShop(shop);

        assertFalse(result.getSuccess());
        assertEquals("店铺ID不能为空", result.getErrorMsg());
        verifyNoInteractions(shopMapper, stringRedisTemplate);
    }

    @Test
    void nearbyQueryReturnsShopsInDistanceOrderWithDistance() {
        Long nearestId = 2L;
        Long fartherId = 1L;
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResultList = Arrays.asList(
                geoResult(nearestId, 120D),
                geoResult(fartherId, 860D)
        );
        when(geoOperations.radius(
                eq(RedisConstants.SHOP_GEO_KEY + 2),
                any(Circle.class),
                any(RedisGeoCommands.GeoRadiusCommandArgs.class)
        )).thenReturn(new GeoResults<>(geoResultList));

        Shop nearest = shop().setId(nearestId).setName("最近的店");
        Shop farther = shop().setId(fartherId).setName("较远的店");
        // 数据库返回顺序不可靠，业务层应按 Redis 的距离顺序重新组装。
        when(shopMapper.selectBatchIds(Arrays.asList(nearestId, fartherId)))
                .thenReturn(Arrays.asList(farther, nearest));

        Result result = shopService.queryShopByType(2, 1, 121.5D, 31.2D);

        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked")
        List<Shop> shops = (List<Shop>) result.getData();
        assertEquals(Arrays.asList(nearest, farther), shops);
        assertEquals(120D, shops.get(0).getDistance());
        assertEquals(860D, shops.get(1).getDistance());
    }

    @Test
    void invalidNearbyCoordinateFailsBeforeRedisOrDatabaseQuery() {
        Result result = shopService.queryShopByType(2, 1, 181D, 31.2D);

        assertFalse(result.getSuccess());
        assertEquals("经纬度无效", result.getErrorMsg());
        verifyNoInteractions(shopMapper, stringRedisTemplate);
    }

    private GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult(Long shopId, double distance) {
        RedisGeoCommands.GeoLocation<String> location = new RedisGeoCommands.GeoLocation<>(
                shopId.toString(),
                new Point(121.5D, 31.2D)
        );
        return new GeoResult<>(location, new Distance(distance));
    }

    private Shop shop() {
        return new Shop()
                .setId(SHOP_ID)
                .setName("测试店铺")
                .setTypeId(2L)
                .setAddress("测试地址");
    }
}
