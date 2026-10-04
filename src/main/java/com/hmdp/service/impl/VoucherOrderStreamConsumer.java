package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "hmdp.voucher-order.stream-consumer.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class VoucherOrderStreamConsumer {

    private static final Duration NEW_MESSAGE_BLOCK_TIME = Duration.ofSeconds(2);
    private static final long PENDING_RETRY_INTERVAL_MILLIS = 5000L;
    private static final long ERROR_BACKOFF_MILLIS = 1000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectProvider<IVoucherOrderService> voucherOrderServiceProvider;
    private final ExecutorService consumerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voucher-order-stream-consumer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;

    public VoucherOrderStreamConsumer(
            StringRedisTemplate stringRedisTemplate,
            ObjectProvider<IVoucherOrderService> voucherOrderServiceProvider
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.voucherOrderServiceProvider = voucherOrderServiceProvider;
    }

    @PostConstruct
    public void start() {
        initializeStreamGroup();
        running = true;
        consumerExecutor.submit(this::consumeLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        consumerExecutor.shutdownNow();
        try {
            consumerExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    void initializeStreamGroup() {
        byte[] streamKey = stringRedisTemplate.getStringSerializer()
                .serialize(RedisConstants.STREAM_ORDERS_KEY);
        if (streamKey == null) {
            throw new IllegalStateException("Redis Stream key序列化失败");
        }

        try {
            stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.xGroupCreate(
                    streamKey,
                    RedisConstants.STREAM_ORDERS_GROUP,
                    ReadOffset.from("0"),
                    true
            ));
            log.info("已创建订单Stream及消费者组，stream={}, group={}",
                    RedisConstants.STREAM_ORDERS_KEY, RedisConstants.STREAM_ORDERS_GROUP);
        } catch (DataAccessException exception) {
            if (!isBusyGroup(exception)) {
                throw exception;
            }
            log.info("订单Stream消费者组已存在，继续启动，stream={}, group={}",
                    RedisConstants.STREAM_ORDERS_KEY, RedisConstants.STREAM_ORDERS_GROUP);
        }
    }

    private void consumeLoop() {
        long nextPendingCheckAt = 0L;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now >= nextPendingCheckAt) {
                    handlePendingList();
                    nextPendingCheckAt = now + PENDING_RETRY_INTERVAL_MILLIS;
                }

                // 4. 读取Stream中的新订单消息，消费者线程不能使用请求线程的UserHolder
                List<MapRecord<String, Object, Object>> records = streamOperations().read(
                        streamConsumer(),
                        StreamReadOptions.empty().count(1).block(NEW_MESSAGE_BLOCK_TIME),
                        StreamOffset.create(RedisConstants.STREAM_ORDERS_KEY, ReadOffset.lastConsumed())
                );
                processRecords(records);
            } catch (Exception exception) {
                if (!running || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("读取或处理订单Stream消息失败，将保留Pending消息后重试", exception);
                sleepAfterFailure();
            }
        }
    }

    void handlePendingList() {
        while (running) {
            List<MapRecord<String, Object, Object>> records = streamOperations().read(
                    streamConsumer(),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(RedisConstants.STREAM_ORDERS_KEY, ReadOffset.from("0"))
            );
            if (records == null || records.isEmpty()) {
                return;
            }

            try {
                processRecord(records.get(0));
            } catch (Exception exception) {
                sleepAfterFailure();
                return;
            }
        }
    }

    private void processRecords(List<MapRecord<String, Object, Object>> records) {
        if (records == null) {
            records = Collections.emptyList();
        }
        for (MapRecord<String, Object, Object> record : records) {
            processRecord(record);
        }
    }

    void processRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        Long orderId = null;
        Long userId = null;
        Long voucherId = null;

        try {
            orderId = parseLong(values.get("id"), "id");
            userId = parseLong(values.get("userId"), "userId");
            voucherId = parseLong(values.get("voucherId"), "voucherId");
            VoucherOrder voucherOrder = new VoucherOrder()
                    .setId(orderId)
                    .setUserId(userId)
                    .setVoucherId(voucherId);
            voucherOrderServiceProvider.getObject().handleVoucherOrder(voucherOrder);
            // 5. 事务正常完成或确认是幂等终态后才能ACK，异常时消息必须留在Pending List
            streamOperations().acknowledge(
                    RedisConstants.STREAM_ORDERS_KEY,
                    RedisConstants.STREAM_ORDERS_GROUP,
                    record.getId()
            );
        } catch (Exception exception) {
            log.error("异步订单处理失败，不确认消息，recordId={}, orderId={}, userId={}, voucherId={}",
                    record.getId(), orderId, userId, voucherId, exception);
            throw exception;
        }
    }

    private StreamOperations<String, Object, Object> streamOperations() {
        return stringRedisTemplate.opsForStream();
    }

    private Consumer streamConsumer() {
        return Consumer.from(
                RedisConstants.STREAM_ORDERS_GROUP,
                RedisConstants.STREAM_ORDERS_CONSUMER
        );
    }

    private Long parseLong(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("订单Stream消息缺少字段：" + fieldName);
        }
        return Long.valueOf(value.toString());
    }

    private boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepAfterFailure() {
        try {
            Thread.sleep(ERROR_BACKOFF_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
