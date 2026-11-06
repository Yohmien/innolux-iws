/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 使用真实 Redis 验证应用会话载荷条件删除；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.auth.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Redis regression for stale root-session logout fan-out.
 */
@Tag("dev")
class IamSessionRegistryRedisTest {

    @Test
    void applicationScopeIsIsolatedByRootSession() {
        String first = IamSessionRegistry.sessionScope(1L, "IWS", "root-a");
        String second = IamSessionRegistry.sessionScope(1L, "IWS", "root-b");

        assertEquals("1:IWS:root-a", first);
        assertEquals("1:IWS:root-b", second);
        assertFalse(first.equals(second));
    }

    @Test
    void stalePayloadCannotDeleteSessionCommittedAfterItWasRead() {
        String port = System.getenv("IWS_TEST_REDIS_PORT");
        Assumptions.assumeTrue(
            port != null && !port.isBlank(),
            "Set IWS_TEST_REDIS_HOST/IWS_TEST_REDIS_PORT to run the real Redis regression"
        );

        String host = environmentOrDefault("IWS_TEST_REDIS_HOST", "127.0.0.1");
        String redisSecret = System.getenv("IWS_TEST_REDIS_PASSWORD");
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        var server = config.useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setDatabase(0);
        if (redisSecret != null && !redisSecret.isBlank()) {
            server.setPassword(redisSecret);
        }

        RedissonClient client = Redisson.create(config);
        String key = "iam:test:session-cas:" + UUID.randomUUID();
        RBucket<String> bucket = client.getBucket(key, StringCodec.INSTANCE);
        try {
            String oldRootPayload = "{\"rootSid\":\"root-a\",\"sessionId\":\"session-a\"}";
            String newRootPayload = "{\"rootSid\":\"root-b\",\"sessionId\":\"session-b\"}";
            bucket.set(oldRootPayload);

            // This is the payload captured by the stale root-session logout fan-out.
            String stalePayload = bucket.get();
            // A concurrent login commits its new session before the stale fan-out deletes.
            bucket.set(newRootPayload);

            assertFalse(IamSessionRegistry.deleteIfPayloadMatches(bucket, stalePayload));
            assertEquals(newRootPayload, bucket.get());

            assertTrue(IamSessionRegistry.deleteIfPayloadMatches(bucket, newRootPayload));
            assertFalse(bucket.isExists());
        } finally {
            bucket.delete();
            client.shutdown();
        }
    }

    @Test
    void applicationSessionLockRenewsBeyondLegacyFifteenSecondLease() throws Exception {
        String port = System.getenv("IWS_TEST_REDIS_PORT");
        Assumptions.assumeTrue(
            port != null && !port.isBlank(),
            "Set IWS_TEST_REDIS_HOST/IWS_TEST_REDIS_PORT to run the real Redis regression"
        );

        String host = environmentOrDefault("IWS_TEST_REDIS_HOST", "127.0.0.1");
        String redisSecret = System.getenv("IWS_TEST_REDIS_PASSWORD");
        Config config = new Config();
        var server = config.useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setDatabase(0);
        if (redisSecret != null && !redisSecret.isBlank()) {
            server.setPassword(redisSecret);
        }

        RedissonClient client = Redisson.create(config);
        RedissonClient contenderClient = Redisson.create(config);
        String key = "iam:test:application-session-lock:" + UUID.randomUUID();
        RLock owner = client.getLock(key);
        RLock contender = contenderClient.getLock(key);
        try {
            assertTrue(owner.tryLock(0, TimeUnit.SECONDS));
            Thread.sleep(16_000L);
            assertTrue(owner.isHeldByCurrentThread());
            assertFalse(contender.tryLock(0, TimeUnit.SECONDS));

            owner.unlock();
            assertTrue(contender.tryLock(0, TimeUnit.SECONDS));
            contender.unlock();
        } finally {
            if (owner.isHeldByCurrentThread()) {
                owner.unlock();
            }
            if (contender.isHeldByCurrentThread()) {
                contender.unlock();
            }
            contenderClient.shutdown();
            client.shutdown();
        }
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
