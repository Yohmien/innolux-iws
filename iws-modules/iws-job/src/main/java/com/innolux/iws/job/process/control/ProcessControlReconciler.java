package com.innolux.iws.job.process.control;

import com.innolux.iws.process.api.ControlIntent;
import com.innolux.iws.process.api.PlatformApplyResult;
import com.innolux.iws.process.api.PlatformControlIntent;
import com.innolux.iws.process.api.PlatformScopeResultCommand;
import com.innolux.iws.process.api.ProcessControlIntentPort;
import com.innolux.iws.process.api.SchedulerPlatformPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 从持久意图补建监督和 worker 物理任务，不执行业务事件。 */
public final class ProcessControlReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessControlReconciler.class);
    private final ProcessControlIntentPort intents;
    private final SchedulerPlatformPort platform;
    private final int batchLimit;

    public ProcessControlReconciler(ProcessControlIntentPort intents, SchedulerPlatformPort platform, int batchLimit) {
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("控制意图协调批次必须为正数");
        }
        this.intents = intents;
        this.platform = platform;
        this.batchLimit = batchLimit;
    }

    /** 每次重新读取持久意图；失败保留原意图，下轮继续。 */
    public void reconcile() {
        for (ControlIntent intent : intents.listPendingIntents(batchLimit)) {
            try {
                PlatformApplyResult result = platform.apply(new PlatformControlIntent(intent.scopeType(),
                    intent.scopeKey(), intent.action(), intent.revision(), intent.jobSpec()));
                intents.recordResult(new PlatformScopeResultCommand(intent.scopeType(), intent.scopeKey(),
                    intent.duty(), intent.action(), intent.revision(), result.applied(), result.observedState(),
                    result.platformJobId(), result.message()));
            } catch (RuntimeException failure) {
                LOG.warn("平台控制意图尚未收敛: scopeType={}, scopeKey={}, revision={}",
                    intent.scopeType(), intent.scopeKey(), intent.revision(), failure);
            }
        }
    }
}
