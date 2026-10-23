/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 恢复前检查点判定；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.EffectResult;

/**
 * 只根据持久检查点决定是否复用、重试或停留。
 */
public final class RecoveryPolicy {

    private RecoveryPolicy() {
    }

    /**
     * 恢复判定。
     *
     * @param prior 最近一次尝试，可为空
     * @param sent 当前事件是否已存在发送意图
     * @return 判定
     */
    public static Decision decide(ProcessAttempt prior, boolean sent) {
        if (prior == null) {
            return sent ? Decision.UNKNOWN : Decision.FRESH;
        }
        boolean hasEvidence = prior.getEvidenceRef() != null || prior.getCheckpointJson() != null;
        if (prior.getEffectResult() == EffectResult.UNKNOWN
            || prior.getCheckpointStatus() == CheckpointStatus.UNKNOWN
            || prior.getCheckpointStatus() == CheckpointStatus.SEND_INTENT) {
            return Decision.UNKNOWN;
        }
        if (hasEvidence && (prior.getEffectResult() == EffectResult.SUCCEEDED
            || prior.getEffectResult() == EffectResult.ACCEPTED
            || prior.getEffectResult() == EffectResult.SKIPPED_MODE
            || prior.getEffectResult() == EffectResult.SKIPPED)) {
            return Decision.COMPLETE;
        }
        if ((prior.getEffectResult() == EffectResult.SUCCEEDED
            || prior.getEffectResult() == EffectResult.ACCEPTED
            || prior.getEffectResult() == EffectResult.SKIPPED_MODE
            || prior.getEffectResult() == EffectResult.SKIPPED) && !hasEvidence) {
            return Decision.UNKNOWN;
        }
        if (prior.getEffectResult() == EffectResult.NO_EFFECT
            || prior.getEffectResult() == EffectResult.FAILED) {
            return sent ? Decision.UNKNOWN : Decision.RETRY_SAFE;
        }
        return sent && !hasEvidence ? Decision.UNKNOWN : Decision.RETRY_SAFE;
    }

    /**
     * 恢复动作。
     */
    public enum Decision {
        COMPLETE,
        RETRY_SAFE,
        UNKNOWN,
        FRESH
    }
}
