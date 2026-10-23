/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督控制意图读取、平台应用与 CAS 回写；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.control;

import com.aizuda.snailjob.common.log.SnailJobLog;
import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.PlatformApplyResult;
import com.innolux.iws.process.api.PlatformControlIntent;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.ProcessSchedulerPort;
import com.innolux.iws.process.api.SchedulerPlatformPort;

/**
 * 在 process 事务外收敛监督平台控制意图。
 */
public class SupervisorControlService {

    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_STOPPED = "STOPPED";
    private static final String ACTION_ENSURE_RUNNING = "ENSURE_RUNNING";
    private static final String ACTION_ENSURE_STOPPED = "ENSURE_STOPPED";
    private static final String ACTION_TRIGGER_ONCE = "TRIGGER_ONCE";
    private static final String SCOPE_SUPERVISOR = "SUPERVISOR";

    private final ProcessSchedulerPort processPort;

    private final SchedulerPlatformPort platformPort;

    /**
     * 构造控制服务。
     *
     * @param processPort process 调度端口
     * @param platformPort SnailJob 控制端口
     */
    public SupervisorControlService(ProcessSchedulerPort processPort, SchedulerPlatformPort platformPort) {
        this.processPort = processPort;
        this.platformPort = platformPort;
    }

    /**
     * 读取并收敛一次监督控制意图。
     *
     * <p>没有待收敛差异时不调用平台。平台结果按 apply 时 revision CAS 回写；新修订已产生时保留新意图。</p>
     *
     * @param supervisorInstanceId 监督实例 ID
     */
    public void synchronize(long supervisorInstanceId) {
        ControlIntentSnapshot snapshot = processPort.flushControlIntent(supervisorInstanceId);
        String action = action(snapshot);
        if (action == null) {
            return;
        }
        PlatformControlIntent intent = new PlatformControlIntent(SCOPE_SUPERVISOR,
            String.valueOf(supervisorInstanceId), action, snapshot.revision(), snapshot.jobSpec());
        PlatformApplyResult result = platformPort.apply(intent);
        PlatformResultCommand command = new PlatformResultCommand(supervisorInstanceId, snapshot.revision(),
            result.applied(), result.observedState(), result.platformJobId(), result.message());
        PlatformResultAck ack = processPort.recordPlatformResult(command);
        if (ack.conflict()) {
            SnailJobLog.REMOTE.warn("监督控制结果未覆盖新意图: supervisorInstanceId={}, currentRevision={}",
                supervisorInstanceId, ack.currentRevision());
        } else if (!ack.recorded()) {
            throw new IllegalStateException("监督控制结果未记录: " + supervisorInstanceId);
        }
        if (!result.applied()) {
            throw new IllegalStateException(result.message());
        }
    }

    private static String action(ControlIntentSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException("监督控制快照为空");
        }
        if (snapshot.jobSpec() == null) {
            throw new IllegalStateException("监督控制缺少 JobSpec");
        }
        String desired = snapshot.desiredState();
        if (STATE_STOPPED.equals(desired)) {
            return ACTION_ENSURE_STOPPED;
        }
        if (!STATE_RUNNING.equals(desired)) {
            throw new IllegalStateException("不支持的监督期望状态: " + desired);
        }
        if (snapshot.wakePending()) {
            return ACTION_TRIGGER_ONCE;
        }
        return STATE_RUNNING.equals(snapshot.observedState()) ? null : ACTION_ENSURE_RUNNING;
    }
}
