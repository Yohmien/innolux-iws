/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 控制意图到 SnailJob 的幂等应用适配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.platform;

import com.aizuda.snailjob.common.log.SnailJobLog;
import com.innolux.iws.process.api.PlatformApplyResult;
import com.innolux.iws.process.api.PlatformControlIntent;
import com.innolux.iws.process.runtime.SchedulerAdapter;

import java.util.Optional;

/**
 * 在 process 事务外应用任务创建、启停与单次触发意图。
 *
 * <p>SnailJob 2.0.2 不返回控制修订号，因此成功结果仍以 0 表示平台修订未知，不能把期望修订伪装成平台观测值。</p>
 */
public class SnailJobSchedulerAdapter implements SchedulerAdapter {

    private static final String ACTION_ENSURE_RUNNING = "ENSURE_RUNNING";
    private static final String ACTION_ENSURE_STOPPED = "ENSURE_STOPPED";
    private static final String ACTION_TRIGGER_ONCE = "TRIGGER_ONCE";
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_STOPPED = "STOPPED";
    private static final String STATE_TRIGGERED = "TRIGGERED";
    private static final String STATE_UNKNOWN = "UNKNOWN";
    private static final long UNKNOWN_REVISION = 0L;

    private final SnailJobOpenApiClient client;

    /**
     * 构造适配器。
     *
     * @param client SnailJob OpenAPI 客户端
     */
    public SnailJobSchedulerAdapter(SnailJobOpenApiClient client) {
        this.client = client;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String adapterKey() {
        return "snail-job-biz-id";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformApplyResult apply(PlatformControlIntent intent) {
        try {
            if (intent == null) {
                return failed("缺少平台控制意图");
            }
            if (intent.scopeType() == null || intent.scopeType().isBlank()
                || intent.scopeKey() == null || intent.scopeKey().isBlank()) {
                return failed("缺少平台控制范围");
            }
            if (intent.desiredRevision() <= 0) {
                return failed("desiredRevision 必须为正数");
            }
            return switch (intent.action()) {
                case ACTION_ENSURE_RUNNING -> ensureRunning(intent);
                case ACTION_ENSURE_STOPPED -> ensureStopped(intent);
                case ACTION_TRIGGER_ONCE -> triggerOnce(intent);
                default -> failed("不支持的控制动作: " + intent.action());
            };
        } catch (RuntimeException ex) {
            SnailJobLog.REMOTE.error("计划事件平台控制失败: {}", ex.getMessage());
            return failed(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private PlatformApplyResult ensureRunning(PlatformControlIntent intent) {
        SnailJobJobDefinition definition = SnailJobJobDefinition.from(intent.jobSpec());
        long jobId = ensure(definition);
        if (!client.updateRunning(definition.bizId(), true)) {
            throw new IllegalStateException("SnailJob 未确认启用任务 " + definition.bizId());
        }
        return applied(jobId, STATE_RUNNING, "SnailJob 任务已确保存在并运行");
    }

    private PlatformApplyResult ensureStopped(PlatformControlIntent intent) {
        String bizId = requireBizId(intent);
        Optional<Long> jobId = client.findJobId(bizId);
        if (jobId.isEmpty()) {
            return applied(null, STATE_STOPPED, "SnailJob 任务不存在，无需停用");
        }
        if (!client.updateRunning(bizId, false)) {
            throw new IllegalStateException("SnailJob 未确认停用任务 " + bizId);
        }
        return applied(jobId.get(), STATE_STOPPED, "SnailJob 任务已停用");
    }

    private PlatformApplyResult triggerOnce(PlatformControlIntent intent) {
        SnailJobJobDefinition definition = SnailJobJobDefinition.from(intent.jobSpec());
        long jobId = ensure(definition);
        if (!client.trigger(definition.bizId())) {
            throw new IllegalStateException("SnailJob 未确认触发任务 " + definition.bizId());
        }
        return applied(jobId, STATE_TRIGGERED, "SnailJob 任务已触发一次");
    }

    private long ensure(SnailJobJobDefinition definition) {
        Optional<Long> existing = client.findJobId(definition.bizId());
        if (existing.isPresent()) {
            if (!client.update(definition)) {
                throw new IllegalStateException("SnailJob 未确认更新任务 " + definition.bizId());
            }
            return existing.get();
        }
        try {
            return client.add(definition);
        } catch (RuntimeException addFailure) {
            Optional<Long> raced = client.findJobId(definition.bizId());
            if (raced.isEmpty()) {
                throw addFailure;
            }
            if (!client.update(definition)) {
                throw new IllegalStateException("SnailJob 创建竞争后未确认更新任务 " + definition.bizId(), addFailure);
            }
            return raced.get();
        }
    }

    private static String requireBizId(PlatformControlIntent intent) {
        if (intent.jobSpec() == null || intent.jobSpec().bizId() == null || intent.jobSpec().bizId().isBlank()) {
            throw new IllegalArgumentException("缺少 jobSpec.bizId");
        }
        return intent.jobSpec().bizId();
    }

    private static PlatformApplyResult applied(Long jobId, String observedState, String message) {
        return new PlatformApplyResult(true, UNKNOWN_REVISION, observedState,
            jobId == null ? null : String.valueOf(jobId), message);
    }

    private static PlatformApplyResult failed(String message) {
        return new PlatformApplyResult(false, UNKNOWN_REVISION, STATE_UNKNOWN, null, message);
    }
}
