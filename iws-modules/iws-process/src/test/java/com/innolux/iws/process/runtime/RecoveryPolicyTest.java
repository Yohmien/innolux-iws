package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.EffectResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** NO_EFFECT 与成功检查点不得混同。 */
@Tag("dev")
class RecoveryPolicyTest {

    @Test
    void noEffectIsSafeRetryAndNeverComplete() {
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setEffectResult(EffectResult.NO_EFFECT);
        attempt.setCheckpointStatus(CheckpointStatus.NO_EFFECT);
        assertThat(RecoveryPolicy.decide(attempt, false)).isEqualTo(RecoveryPolicy.Decision.RETRY_SAFE);
    }

    @Test
    void unknownWithSendIntentMustNotRetry() {
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setEffectResult(EffectResult.UNKNOWN);
        attempt.setCheckpointStatus(CheckpointStatus.UNKNOWN);
        assertThat(RecoveryPolicy.decide(attempt, false)).isEqualTo(RecoveryPolicy.Decision.UNKNOWN);
    }

    @Test
    void successWithEvidenceCompletes() {
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setEffectResult(EffectResult.SUCCEEDED);
        attempt.setCheckpointStatus(CheckpointStatus.SUCCEEDED);
        attempt.setEvidenceRef("receipt:1");
        assertThat(RecoveryPolicy.decide(attempt, false)).isEqualTo(RecoveryPolicy.Decision.COMPLETE);
    }

    @Test
    void successClaimWithoutEvidenceIsUnknown() {
        ProcessAttempt attempt = new ProcessAttempt();
        attempt.setEffectResult(EffectResult.SUCCEEDED);
        attempt.setCheckpointStatus(CheckpointStatus.SUCCEEDED);
        assertThat(RecoveryPolicy.decide(attempt, false)).isEqualTo(RecoveryPolicy.Decision.UNKNOWN);
    }
}
