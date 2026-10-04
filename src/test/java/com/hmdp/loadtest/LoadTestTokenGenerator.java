package com.hmdp.loadtest;

import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动运行的 JMeter 登录态数据生成器。
 *
 * 类名故意不以 Test 结尾，因此普通 mvn test 不会执行它。请通过
 * scripts/prepare-jmeter-tokens.sh 显式运行。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "hmdp.voucher-order.stream-consumer.enabled=false"
)
class LoadTestTokenGenerator {

    private static final Set<String> ALLOWED_USER_FIELDS = new HashSet<>();

    static {
        ALLOWED_USER_FIELDS.add("id");
        ALLOWED_USER_FIELDS.add("nickName");
        ALLOWED_USER_FIELDS.add("icon");
    }

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void generateAndVerifyJMeterTokens() throws IOException {
        int requestedUserCount = Integer.getInteger("loadtest.user-count", 1000);
        assertTrue(requestedUserCount > 0, "loadtest.user-count 必须大于 0");

        Path outputDirectory = Paths.get(
                System.getProperty("loadtest.output-dir", "load-test")
        ).toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);

        List<User> users = userService.lambdaQuery()
                .orderByAsc(User::getId)
                .last("LIMIT " + requestedUserCount)
                .list();
        assertEquals(
                requestedUserCount,
                users.size(),
                "MySQL 中可用用户不足 " + requestedUserCount + " 个"
        );

        Path tokenTempFile = Files.createTempFile(outputDirectory, "tokens-", ".tmp");
        Path auditTempFile = Files.createTempFile(outputDirectory, "token-users-", ".tmp");
        Path reportTempFile = Files.createTempFile(outputDirectory, "token-verification-", ".tmp");

        List<String> tokens = new ArrayList<>(requestedUserCount);
        Map<String, Long> expectedUserIds = new LinkedHashMap<>();
        List<String> createdRedisKeys = new ArrayList<>(requestedUserCount);

        try {
            List<String> auditLines = new ArrayList<>(requestedUserCount + 1);
            auditLines.add("user_id,token");

            for (User user : users) {
                assertNotNull(user.getId(), "压测用户 ID 不能为空");
                String token = nextUniqueToken(tokens);
                String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

                Map<String, String> userMap = new LinkedHashMap<>();
                userMap.put("id", user.getId().toString());
                if (user.getNickName() != null) {
                    userMap.put("nickName", user.getNickName());
                }
                if (user.getIcon() != null) {
                    userMap.put("icon", user.getIcon());
                }

                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                createdRedisKeys.add(tokenKey);
                Boolean expirationSet = stringRedisTemplate.expire(
                        tokenKey,
                        RedisConstants.LOGIN_USER_TTL,
                        TimeUnit.MINUTES
                );
                assertTrue(Boolean.TRUE.equals(expirationSet), "Token TTL 设置失败：" + tokenKey);

                tokens.add(token);
                expectedUserIds.put(token, user.getId());
                auditLines.add(user.getId() + "," + token);
            }

            Files.write(tokenTempFile, tokens, StandardCharsets.UTF_8);
            Files.write(auditTempFile, auditLines, StandardCharsets.UTF_8);

            VerificationSummary summary = verifyGeneratedData(
                    tokenTempFile,
                    auditTempFile,
                    tokens,
                    expectedUserIds
            );
            Files.write(
                    reportTempFile,
                    buildReport(outputDirectory, requestedUserCount, summary),
                    StandardCharsets.UTF_8
            );

            moveReplacing(tokenTempFile, outputDirectory.resolve("tokens.txt"));
            moveReplacing(auditTempFile, outputDirectory.resolve("token-users.csv"));
            moveReplacing(reportTempFile, outputDirectory.resolve("token-verification.txt"));

            System.out.println("JMeter 压测登录态准备完成");
            System.out.println("用户数：" + requestedUserCount);
            System.out.println("tokens.txt：" + outputDirectory.resolve("tokens.txt"));
            System.out.println("用户映射：" + outputDirectory.resolve("token-users.csv"));
            System.out.println("核对报告：" + outputDirectory.resolve("token-verification.txt"));
            System.out.println("Redis TTL 范围：" + summary.minimumTtlSeconds + "～"
                    + summary.maximumTtlSeconds + " 秒");
        } catch (RuntimeException | IOException exception) {
            if (!createdRedisKeys.isEmpty()) {
                stringRedisTemplate.delete(createdRedisKeys);
            }
            Files.deleteIfExists(tokenTempFile);
            Files.deleteIfExists(auditTempFile);
            Files.deleteIfExists(reportTempFile);
            throw exception;
        }
    }

    private String nextUniqueToken(List<String> existingTokens) {
        Set<String> existing = new HashSet<>(existingTokens);
        String token;
        do {
            token = UUID.randomUUID().toString();
        } while (existing.contains(token)
                || Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.LOGIN_USER_KEY + token)));
        return token;
    }

    private VerificationSummary verifyGeneratedData(
            Path tokenFile,
            Path auditFile,
            List<String> expectedTokens,
            Map<String, Long> expectedUserIds
    ) throws IOException {
        List<String> fileTokens = Files.readAllLines(tokenFile, StandardCharsets.UTF_8);
        assertEquals(expectedTokens, fileTokens, "tokens.txt 内容与本次生成的 Token 不一致");
        assertEquals(expectedTokens.size(), new HashSet<>(fileTokens).size(), "tokens.txt 中存在重复 Token");

        List<String> auditLines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        assertEquals(expectedTokens.size() + 1, auditLines.size(), "token-users.csv 行数不正确");
        assertEquals("user_id,token", auditLines.get(0), "token-users.csv 表头不正确");

        long minimumTtlSeconds = Long.MAX_VALUE;
        long maximumTtlSeconds = Long.MIN_VALUE;
        for (int index = 0; index < fileTokens.size(); index++) {
            String token = fileTokens.get(index);
            Long expectedUserId = expectedUserIds.get(token);
            assertNotNull(expectedUserId, "文件 Token 没有对应用户：" + token);
            assertEquals(
                    expectedUserId + "," + token,
                    auditLines.get(index + 1),
                    "Token 与用户审计映射不一致"
            );

            String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
            Map<Object, Object> redisUser = stringRedisTemplate.opsForHash().entries(tokenKey);
            assertFalse(redisUser.isEmpty(), "Redis 中缺少 Token：" + token);
            assertEquals(expectedUserId.toString(), redisUser.get("id"), "Redis Token 对应的用户 ID 不正确");

            for (Object field : redisUser.keySet()) {
                assertTrue(ALLOWED_USER_FIELDS.contains(field.toString()), "Redis 登录态包含敏感字段：" + field);
            }
            assertFalse(redisUser.containsKey("phone"), "Redis 登录态不能保存手机号");
            assertFalse(redisUser.containsKey("password"), "Redis 登录态不能保存密码");

            Long ttlSeconds = stringRedisTemplate.getExpire(tokenKey, TimeUnit.SECONDS);
            assertNotNull(ttlSeconds, "无法读取 Token TTL：" + token);
            assertTrue(ttlSeconds > 0, "Token 已过期或未设置 TTL：" + token);
            assertTrue(
                    ttlSeconds <= TimeUnit.MINUTES.toSeconds(RedisConstants.LOGIN_USER_TTL),
                    "Token TTL 超过项目登录有效期：" + token
            );
            minimumTtlSeconds = Math.min(minimumTtlSeconds, ttlSeconds);
            maximumTtlSeconds = Math.max(maximumTtlSeconds, ttlSeconds);
        }

        return new VerificationSummary(
                fileTokens.size(),
                minimumTtlSeconds,
                maximumTtlSeconds,
                fileTokens.get(0).substring(0, 8)
        );
    }

    private List<String> buildReport(
            Path outputDirectory,
            int requestedUserCount,
            VerificationSummary summary
    ) {
        List<String> report = new ArrayList<>();
        report.add("verification=PASS");
        report.add("generatedAt=" + OffsetDateTime.now());
        report.add("requestedUsers=" + requestedUserCount);
        report.add("verifiedTokens=" + summary.verifiedTokens);
        report.add("redisKeyPrefix=" + RedisConstants.LOGIN_USER_KEY);
        report.add("ttlMinutes=" + RedisConstants.LOGIN_USER_TTL);
        report.add("minimumTtlSeconds=" + summary.minimumTtlSeconds);
        report.add("maximumTtlSeconds=" + summary.maximumTtlSeconds);
        report.add("sampleTokenPrefix=" + summary.sampleTokenPrefix + "...");
        report.add("tokensFile=" + outputDirectory.resolve("tokens.txt"));
        report.add("auditFile=" + outputDirectory.resolve("token-users.csv"));
        report.add("redisAndFileMapping=ONE_TO_ONE");
        report.add("sensitiveFieldsStored=false");
        return report;
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static class VerificationSummary {
        private final int verifiedTokens;
        private final long minimumTtlSeconds;
        private final long maximumTtlSeconds;
        private final String sampleTokenPrefix;

        private VerificationSummary(
                int verifiedTokens,
                long minimumTtlSeconds,
                long maximumTtlSeconds,
                String sampleTokenPrefix
        ) {
            this.verifiedTokens = verifiedTokens;
            this.minimumTtlSeconds = minimumTtlSeconds;
            this.maximumTtlSeconds = maximumTtlSeconds;
            this.sampleTokenPrefix = sampleTokenPrefix;
        }
    }
}
