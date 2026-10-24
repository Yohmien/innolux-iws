/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督控制 apply 与 CAS 回写测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.control;

import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.api.JobSpec;
import com.innolux.iws.process.api.PlatformApplyResult;
import com.innolux.iws.process.api.PlatformControlIntent;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.ProcessSchedulerPort;
import com.innolux.iws.process.api.SchedulerPlatformPort;
import com.innolux.iws.process.api.SupervisorTickResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 读取快照后才应用平台，结果携带 apply 时 revision CAS 回写。
 */
@Tag("dev")
class SupervisorControlServiceTest {

    @Test
    void runningAbsentCreatesAndRecordsWithOriginalRevision() {
        FakeProcess process = new FakeProcess(snapshot("RUNNING", "ABSENT", false));
        FakePlatform platform = new FakePlatform();

        new SupervisorControlService(process, platform).synchronize(42L);

        assertThat(platform.intent.action()).isEqualTo("ENSURE_RUNNING");
        assertThat(platform.intent.desiredRevision()).isEqualTo(7L);
        assertThat(platform.intent.scopeType()).isEqualTo("SUPERVISOR");
        assertThat(process.command.expectedControlRevision()).isEqualTo(7L);
        assertThat(process.command.applied()).isTrue();
        assertThat(process.command.observedState()).isEqualTo("RUNNING");
    }

    @Test
    void stoppedDesiredAlwaysEnsuresStopped() {
        FakeProcess process = new FakeProcess(snapshot("STOPPED", "RUNNING", true));
        FakePlatform platform = new FakePlatform();

        new SupervisorControlService(process, platform).synchronize(42L);

        assertThat(platform.intent.action()).isEqualTo("ENSURE_STOPPED");
    }

    @Test
    void wakePendingTriggersOnce() {
        FakeProcess process = new FakeProcess(snapshot("RUNNING", "RUNNING", true));
        FakePlatform platform = new FakePlatform();

        new SupervisorControlService(process, platform).synchronize(42L);

        assertThat(platform.intent.action()).isEqualTo("TRIGGER_ONCE");
    }

    @Test
    void convergedRunningStateSkipsPlatformCall() {
        FakeProcess process = new FakeProcess(snapshot("RUNNING", "RUNNING", false));
        FakePlatform platform = new FakePlatform();

        new SupervisorControlService(process, platform).synchronize(42L);

        assertThat(platform.intent).isNull();
        assertThat(process.command).isNull();
    }

    @Test
    void platformFailureIsRecordedAndReturnedAsFailure() {
        FakeProcess process = new FakeProcess(snapshot("RUNNING", "ABSENT", false));
        FakePlatform platform = new FakePlatform();
        platform.result = new PlatformApplyResult(false, 0L, "UNKNOWN", null, "platform down");

        assertThatThrownBy(() -> new SupervisorControlService(process, platform).synchronize(42L))
            .hasMessage("platform down");
        assertThat(process.command.applied()).isFalse();
        assertThat(process.command.message()).isEqualTo("platform down");
    }

    @Test
    void revisionConflictKeepsNewIntentWithoutFailingCurrentApply() {
        FakeProcess process = new FakeProcess(snapshot("RUNNING", "ABSENT", false));
        process.ack = new PlatformResultAck(false, true, 9L, "RUNNING");
        FakePlatform platform = new FakePlatform();

        new SupervisorControlService(process, platform).synchronize(42L);

        assertThat(platform.intent.action()).isEqualTo("ENSURE_RUNNING");
        assertThat(process.command.expectedControlRevision()).isEqualTo(7L);
    }

    @Test
    void unknownDesiredStateFailsBeforePlatformCall() {
        FakeProcess process = new FakeProcess(snapshot("PAUSED", "ABSENT", false));
        FakePlatform platform = new FakePlatform();

        assertThatThrownBy(() -> new SupervisorControlService(process, platform).synchronize(42L))
            .hasMessageContaining("不支持的监督期望状态");
        assertThat(platform.intent).isNull();
    }

    private ControlIntentSnapshot snapshot(String desired, String observed, boolean wakePending) {
        return new ControlIntentSnapshot(42L, desired, observed, 7L, wakePending, null,
            new JobSpec("businessScheduleExecutor", "计划监督－入库－队列－越南",
                "process:supervisor:42", "CRON", "0 */1 * * * ?", null, 60000L, 0,
                "{\"supervisorInstanceId\":42,\"batchLimit\":5,\"maxDurationMs\":5000}", "DEFAULT"),
            null);
    }

    private static final class FakeProcess implements ProcessSchedulerPort {

        private final ControlIntentSnapshot snapshot;
        private PlatformResultAck ack = new PlatformResultAck(true, false, 8L, "RUNNING");
        private PlatformResultCommand command;

        private FakeProcess(ControlIntentSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public SupervisorTickResult tickSupervisor(long supervisorInstanceId, int batchLimit, long maxDurationMs) {
            return null;
        }

        @Override
        public EventBatchResult runEventBatch(long scheduleId, int batchLimit, long maxDurationMs) {
            return null;
        }

        @Override
        public ControlIntentSnapshot flushControlIntent(long supervisorInstanceId) {
            return snapshot;
        }

        @Override
        public PlatformResultAck recordPlatformResult(PlatformResultCommand command) {
            this.command = command;
            return ack;
        }
    }

    private static final class FakePlatform implements SchedulerPlatformPort {

        private PlatformControlIntent intent;
        private PlatformApplyResult result = new PlatformApplyResult(true, 0L, "RUNNING", "101", "ok");

        @Override
        public PlatformApplyResult apply(PlatformControlIntent intent) {
            this.intent = intent;
            return result;
        }
    }
}
