/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Admin 监控统一身份接入
 * @Version      : V1.0
 * @Description  : 监控本地退出后对未确认的中心根会话撤销进行持久重试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.monitor.admin.service;

import cn.hutool.core.util.StrUtil;
import com.innolux.iws.common.auth.domain.IamSessionStatusRequest;
import com.innolux.iws.monitor.admin.config.IamMonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Admin 监控中心撤销待确认队列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IamMonitorPendingRevocationService {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final IamMonitorProperties properties;
    private final IamMonitorCenterClient centerClient;

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
            return new PendingRevocationResult(false, false, true, false);
        }
        try {
            boolean revoked = centerClient.revoke(
                request.rootSid(),
                request.frontendId(),
                request.sessionId(),
                request.sessionGeneration(),
                request.terminal()
            );
            if (!revoked) {
                remove(payload(request));
                return new PendingRevocationResult(false, false, true, false);
            }
        } catch (RuntimeException centerError) {
            try {
                enqueue(payload(request));
            } catch (RuntimeException persistenceError) {
                log.error(
                    "监控本地退出完成，但中心撤销未确认且待重试任务未能持久登记: {}",
                    abbrev(request.rootSid()),
                    persistenceError
                );
                return new PendingRevocationResult(false, false, false, true);
            }
            log.warn("监控本地退出完成，中心根会话撤销待重试: {}", abbrev(request.rootSid()));
            return new PendingRevocationResult(false, true, false, false);
        }
        try {
            remove(payload(request));
        } catch (RuntimeException persistenceError) {
            log.error(
                "监控中心根会话已撤销，但待重试队列清理失败: {}",
                abbrev(request.rootSid()),
                persistenceError
            );
            return new PendingRevocationResult(true, false, false, true);
        }
        return new PendingRevocationResult(true, false, false, false);
    }

    /**
     * 周期重试待确认的中心撤销。
     */
    @Scheduled(fixedDelayString = "${iam.client.pending-revoke-retry-ms:5000}")
    public void retryPending() {
        Set<String> pending;
        try {
            pending = load();
        } catch (RuntimeException persistenceError) {
            log.error("监控中心撤销待确认队列读取失败，本轮不重试: {}", persistenceError.getMessage());
            return;
        }
        for (String rootSid : pending) {
            IamSessionStatusRequest request = parse(rootSid);
            if (request == null) {
                // 旧版本队列只有 rootSid，无法证明仍指向当前占位，安全丢弃。
                remove(rootSid);
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
                remove(rootSid);
                if (revoked) {
                    log.info("监控中心根会话撤销重试成功: {}", abbrev(rootSid));
                } else {
                    log.info("监控旧应用会话已失效，未撤销当前中心根会话: {}", abbrev(rootSid));
                }
            } catch (RuntimeException error) {
                log.warn("监控中心根会话撤销重试未完成: {} ({})", abbrev(rootSid), error.getMessage());
            }
        }
    }

    private synchronized void enqueue(String payload) {
        Set<String> pending = load();
        pending.add(payload);
        save(pending);
    }

    private synchronized void remove(String payload) {
        Set<String> pending = load();
        if (pending.remove(payload)) {
            save(pending);
        }
    }

    private String payload(IamSessionStatusRequest request) {
        return JSON_MAPPER.writeValueAsString(request);
    }

    private IamSessionStatusRequest parse(String payload) {
        if (payload == null || !payload.trim().startsWith("{")) {
            return null;
        }
        try {
            IamSessionStatusRequest request = JSON_MAPPER.readValue(
                payload,
                IamSessionStatusRequest.class
            );
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

    private synchronized Set<String> load() {
        Path file = pendingFile();
        if (!Files.exists(file)) {
            return new LinkedHashSet<>();
        }
        try {
            Set<String> pending = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (StrUtil.isNotBlank(line)) {
                    pending.add(line.trim());
                }
            }
            return pending;
        } catch (IOException error) {
            log.error("监控中心撤销待确认文件读取失败: {}", file, error);
            throw new QueuePersistenceException("监控中心撤销待确认文件读取失败", error);
        }
    }

    private synchronized void save(Set<String> pending) {
        Path file = pendingFile();
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.write(temp, pending, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
            log.error("监控中心撤销待确认文件写入失败: {}", file, error);
            throw new QueuePersistenceException("监控中心撤销待确认文件写入失败", error);
        }
    }

    private Path pendingFile() {
        String configured = properties.getPendingRevokeFile();
        if (StrUtil.isNotBlank(configured)) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(
            System.getProperty("java.io.tmpdir"),
            "iws-iam",
            "pending-revoke-" + properties.getApplicationId() + ".txt"
        ).toAbsolutePath().normalize();
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
     * @param persistenceFailed 队列持久化是否失败
     */
    public record PendingRevocationResult(
        boolean centerRevoked,
        boolean pending,
        boolean stale,
        boolean persistenceFailed
    ) {

        /**
         * 兼容仅关心中心撤销和待重试状态的调用方。
         *
         * @param centerRevoked 是否已确认撤销
         * @param pending       是否已登记待重试
         */
        public PendingRevocationResult(boolean centerRevoked, boolean pending) {
            this(centerRevoked, pending, false, false);
        }
    }

    /**
     * 队列文件读写失败，供上层区分“已登记”和“未登记”。
     */
    private static final class QueuePersistenceException extends RuntimeException {

        /**
         * @param message 错误信息
         * @param cause   原因
         */
        private QueuePersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
