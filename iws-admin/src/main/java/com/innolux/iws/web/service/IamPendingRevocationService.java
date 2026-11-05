/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 本地退出后对未确认的中心根会话撤销进行持久重试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.hutool.core.util.StrUtil;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.common.redis.utils.RedisUtils;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.web.config.IamClientProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * IWS 中心撤销待确认队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IamPendingRevocationService {

    private static final String KEY_PREFIX = "iam:pending-revoke:";
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IamClientProperties properties;
    private final IamCenterClient centerClient;

    /**
     * 立即撤销中心根会话；失败时持久登记并等待重试。
     *
     * @param request 当前应用会话上下文
     * @return 撤销结果
     */
    public PendingRevocationResult revokeOrQueue(IamSessionStatusRequest request) {
        if (request == null || StrUtil.isBlank(request.rootSid())
            || StrUtil.isBlank(request.applicationId())
            || StrUtil.isBlank(request.frontendId())
            || StrUtil.isBlank(request.sessionId())
            || request.sessionGeneration() <= 0
            || StrUtil.isBlank(request.terminal())) {
            return new PendingRevocationResult(false, false, true);
        }
        try {
            boolean revoked = centerClient.revoke(
                request.rootSid(),
                request.frontendId(),
                request.sessionId(),
                request.sessionGeneration(),
                request.terminal()
            );
            remove(payload(request));
            return new PendingRevocationResult(revoked, false, !revoked);
        } catch (RuntimeException error) {
            enqueue(payload(request));
            log.warn("IWS 本地退出完成，中心根会话撤销待重试: {}", abbrev(request.rootSid()));
            return new PendingRevocationResult(false, true, false);
        }
    }

    /**
     * 周期重试待确认的中心撤销。
     */
    @Scheduled(fixedDelayString = "${iam.client.pending-revoke-retry-ms:5000}")
    public void retryPending() {
        RSet<String> pending = pendingSet();
        for (String payload : pending) {
            IamSessionStatusRequest request = parse(payload);
            if (request == null) {
                // 旧版本队列只有 rootSid，无法证明仍指向当前占位，安全丢弃。
                pending.remove(payload);
                continue;
            }
            try {
                boolean revoked = centerClient.revoke(
                    request.rootSid(),
                    request.frontendId(),
                    request.sessionId(),
                    request.sessionGeneration(),
                    request.terminal()
                );
                pending.remove(payload);
                if (revoked) {
                    log.info("IWS 中心根会话撤销重试成功: {}", abbrev(request.rootSid()));
                } else {
                    log.info("IWS 旧应用会话已失效，未撤销当前中心根会话: {}", abbrev(request.rootSid()));
                }
            } catch (RuntimeException ignored) {
                pending.expire(RETENTION);
            }
        }
    }

    private void enqueue(String payload) {
        RSet<String> pending = pendingSet();
        pending.add(payload);
        pending.expire(RETENTION);
    }

    private void remove(String payload) {
        pendingSet().remove(payload);
    }

    private String payload(IamSessionStatusRequest request) {
        return JsonUtils.toJsonString(request);
    }

    private IamSessionStatusRequest parse(String payload) {
        if (payload == null || !payload.trim().startsWith("{")) {
            return null;
        }
        try {
            IamSessionStatusRequest request = JsonUtils.parseObject(payload, IamSessionStatusRequest.class);
            if (request == null || StrUtil.isBlank(request.rootSid())
                || StrUtil.isBlank(request.applicationId())
                || StrUtil.isBlank(request.frontendId())
                || StrUtil.isBlank(request.sessionId())
                || request.sessionGeneration() <= 0
                || StrUtil.isBlank(request.terminal())) {
                return null;
            }
            return request;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private RSet<String> pendingSet() {
        return RedisUtils.getClient().getSet(KEY_PREFIX + properties.getApplicationId());
    }

    private String abbrev(String rootSid) {
        return rootSid.length() <= 8 ? "***" : "***" + rootSid.substring(rootSid.length() - 8);
    }

    /**
     * 中心撤销结果。
     *
     * @param centerRevoked 是否已确认撤销
     * @param pending       是否已登记待重试
     * @param stale         是否因旧应用会话无权撤销而终结
     */
    public record PendingRevocationResult(boolean centerRevoked, boolean pending, boolean stale) {
    }
}
