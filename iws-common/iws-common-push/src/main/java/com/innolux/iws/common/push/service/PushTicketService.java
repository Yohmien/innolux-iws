/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 使用共享 Redis 原子消费 WebSocket 短时握手票据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.push.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * WebSocket 握手票据存储服务。
 */
@RequiredArgsConstructor
public class PushTicketService {

    private static final String KEY_PREFIX = "iam:push-ticket:";

    private final RedissonClient redissonClient;

    /**
     * 创建服务端保存的短时票据，浏览器只持有随机票据值。
     *
     * @param value   长期 Token 与绑定渠道
     * @param timeout 有效期秒数
     * @return 随机票据
     */
    public String issue(String value, long timeout) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        bucket(ticket).set(value, timeout, TimeUnit.SECONDS);
        return ticket;
    }

    /**
     * 原子读取并删除票据；并发握手时只有一个调用方能够取得值。
     *
     * @param ticket 随机票据
     * @return 票据内容；不存在或已消费时为空
     */
    public String consume(String ticket) {
        return bucket(ticket).getAndDelete();
    }

    private RBucket<String> bucket(String ticket) {
        return redissonClient.getBucket(KEY_PREFIX + ticket);
    }
}
