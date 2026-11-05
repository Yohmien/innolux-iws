/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 在无新 HTTP 请求时复核并关闭失效的 SSE/WS 推送连接；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.service;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.push.core.PushSessionIdentity;
import com.innolux.iws.common.push.core.SseEmitterSessionManager;
import com.innolux.iws.common.push.core.WebSocketSessionManager;
import com.innolux.iws.web.config.IamClientProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 推送长连接身份巡检。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IamPushSessionGuard {

    private final IamSessionValidationService validationService;
    private final IamClientProperties properties;
    private final ObjectProvider<SseEmitterSessionManager> sseManagers;
    private final ObjectProvider<WebSocketSessionManager> webSocketManagers;
    private final ExecutorService cleanupExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 周期复核当前节点全部推送连接，窗口复用根会话校验间隔。
     */
    @Scheduled(
        fixedDelayString = "${iam.client.root-validation-interval-seconds:15}",
        timeUnit = TimeUnit.SECONDS
    )
    public void validateConnections() {
        List<PushSessionIdentity> identities = connections();
        if (identities.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CompletionService<ConnectionResult> completion = new ExecutorCompletionService<>(executor);
        Map<Future<ConnectionResult>, PushSessionIdentity> pending = new IdentityHashMap<>();
        for (PushSessionIdentity identity : identities) {
            Future<ConnectionResult> future = completion.submit(() -> validateOne(identity));
            pending.put(future, identity);
        }
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(Math.max(
                1000,
                properties.getResponseTimeoutMs() + 1000
            ));
        try {
            while (!pending.isEmpty()) {
                Future<ConnectionResult> future = completion.poll();
                if (future == null) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (future == null) {
                        break;
                    }
                }
                PushSessionIdentity identity = pending.remove(future);
                if (identity == null) {
                    continue;
                }
                applyCompleted(future, identity);
            }
            Future<ConnectionResult> completed;
            while ((completed = completion.poll()) != null) {
                PushSessionIdentity identity = pending.remove(completed);
                if (identity != null) {
                    applyCompleted(completed, identity);
                }
            }
        } finally {
            for (Map.Entry<Future<ConnectionResult>, PushSessionIdentity> entry : pending.entrySet()) {
                entry.getKey().cancel(true);
                invalidate(entry.getValue(), "推送连接校验超过总时间预算");
            }
            executor.shutdownNow();
        }
    }

    /**
     * 处理一个已完成的连接校验结果。
     *
     * @param future   已完成任务
     * @param identity 连接标识
     */
    private void applyCompleted(
        Future<ConnectionResult> future,
        PushSessionIdentity identity
    ) {
        try {
            ConnectionResult connectionResult = future.get();
            if (!connectionResult.result().valid()) {
                invalidate(identity, connectionResult.result().message());
            }
        } catch (Exception error) {
            future.cancel(true);
            invalidate(identity, "推送连接校验异常");
            log.warn("推送连接校验失败: userId={}, error={}", identity.userId(),
                error.getMessage());
        }
    }

    /**
     * 立即关闭指定本地 Token 对应的推送连接。
     *
     * @param userId 用户主键
     * @param token  本地登录 Token
     */
    public void close(Long userId, String token) {
        kick(new PushSessionIdentity(userId, token));
    }

    /**
     * 校验单个连接，异常由调用方统一按失败关闭处理。
     *
     * @param identity 连接标识
     * @return 校验结果
     */
    private ConnectionResult validateOne(PushSessionIdentity identity) {
        // 推送连接没有新的 HTTP 写入可证明用户仍在操作，中心失联时更早失败关闭。
        return new ConnectionResult(validationService.validate(identity.token(), false));
    }

    /**
     * 关闭失效连接并清理本地 Token。
     *
     * @param identity 连接标识
     * @param reason   失效原因
     */
    private void invalidate(PushSessionIdentity identity, String reason) {
        kick(identity);
        cleanupExecutor.submit(() -> {
            try {
                StpUtil.logoutByTokenValue(identity.token());
            } catch (Exception error) {
                log.warn("推送连接失效后清理本地 Token 失败: {}", error.getMessage());
            }
        });
        log.info("推送连接身份已失效: userId={}, reason={}", identity.userId(), reason);
    }

    /**
     * 汇总当前节点各消息传输方式的连接。
     *
     * @return 去重后的连接快照
     */
    private List<PushSessionIdentity> connections() {
        List<PushSessionIdentity> identities = new ArrayList<>();
        SseEmitterSessionManager sseManager = sseManagers.getIfAvailable();
        if (sseManager != null) {
            identities.addAll(sseManager.activeSessions());
        }
        WebSocketSessionManager webSocketManager = webSocketManagers.getIfAvailable();
        if (webSocketManager != null) {
            identities.addAll(webSocketManager.activeSessions());
        }
        return identities.stream()
            .filter(identity -> identity.userId() != null && identity.token() != null)
            .distinct()
            .toList();
    }

    /**
     * 关闭指定连接。
     *
     * @param identity 连接标识
     */
    private void kick(PushSessionIdentity identity) {
        try {
            SseEmitterSessionManager sseManager = sseManagers.getIfAvailable();
            if (sseManager != null) {
                sseManager.kick(identity.userId(), identity.token());
            }
        } catch (Exception error) {
            log.warn("关闭 SSE 推送连接失败: userId={}, error={}", identity.userId(),
                error.getMessage());
        }
        try {
            WebSocketSessionManager webSocketManager = webSocketManagers.getIfAvailable();
            if (webSocketManager != null) {
                webSocketManager.kick(identity.userId(), identity.token());
            }
        } catch (Exception error) {
            log.warn("关闭 WebSocket 推送连接失败: userId={}, error={}", identity.userId(),
                error.getMessage());
        }
    }

    /**
     * 停止异步 Token 清理线程。
     */
    @PreDestroy
    public void destroy() {
        cleanupExecutor.shutdownNow();
    }

    /**
     * 单连接校验结果容器，确保任务异常不会直接终止整轮巡检。
     *
     * @param result 校验结果
     */
    private record ConnectionResult(IamSessionValidationService.ValidationResult result) {
    }
}
