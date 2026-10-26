/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : SnailJob 控制意图幂等应用测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.platform;

import com.innolux.iws.process.api.JobSpec;
import com.innolux.iws.process.api.PlatformApplyResult;
import com.innolux.iws.process.api.PlatformControlIntent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控制适配器只通过 SnailJob BizId 客户端执行幂等 ensure。
 */
@Tag("dev")
class SnailJobSchedulerAdapterTest {

    @Test
    void ensureRunningCreatesMissingJobThenEnablesIt() {
        FakeClient client = new FakeClient();
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult result = adapter.apply(intent("ENSURE_RUNNING"));

        assertThat(result.applied()).isTrue();
        assertThat(result.observedState()).isEqualTo("RUNNING");
        assertThat(result.platformJobId()).isEqualTo("101");
        assertThat(result.observedRevision()).isZero();
        assertThat(client.calls).containsExactly("find", "add", "status:true");
    }

    @Test
    void ensureRunningUpdatesExistingJobWithoutRecreating() {
        FakeClient client = new FakeClient();
        client.existingId = 77L;
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult result = adapter.apply(intent("ENSURE_RUNNING"));

        assertThat(result.applied()).isTrue();
        assertThat(result.platformJobId()).isEqualTo("77");
        assertThat(client.calls).containsExactly("find", "update", "status:true");
    }

    @Test
    void ensureStoppedDoesNotCreateAbsentJob() {
        FakeClient client = new FakeClient();
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult result = adapter.apply(intent("ENSURE_STOPPED"));

        assertThat(result.applied()).isTrue();
        assertThat(result.observedState()).isEqualTo("STOPPED");
        assertThat(result.platformJobId()).isNull();
        assertThat(client.calls).containsExactly("find");
    }

    @Test
    void ensureStoppedDisablesExistingJob() {
        FakeClient client = new FakeClient();
        client.existingId = 88L;
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult result = adapter.apply(intent("ENSURE_STOPPED"));

        assertThat(result.applied()).isTrue();
        assertThat(result.platformJobId()).isEqualTo("88");
        assertThat(client.calls).containsExactly("find", "status:false");
    }

    @Test
    void triggerOnceEnsuresDefinitionThenTriggers() {
        FakeClient client = new FakeClient();
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult result = adapter.apply(intent("TRIGGER_ONCE"));

        assertThat(result.applied()).isTrue();
        assertThat(result.observedState()).isEqualTo("TRIGGERED");
        assertThat(client.calls).containsExactly("find", "add", "trigger");
    }

    @Test
    void duplicateAddRaceFallsBackToUpdate() {
        FakeClient client = new FakeClient();
        client.addFailure = new IllegalStateException("任务已存在");
        client.idAfterAddFailure = 99L;
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult result = adapter.apply(intent("ENSURE_RUNNING"));

        assertThat(result.applied()).isTrue();
        assertThat(result.platformJobId()).isEqualTo("99");
        assertThat(client.calls).containsExactly("find", "add", "find", "update", "status:true");
    }

    @Test
    void platformRejectionReturnsFailureWithoutFabricatedSuccess() {
        FakeClient client = new FakeClient();
        client.statusResult = false;
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult result = adapter.apply(intent("ENSURE_RUNNING"));

        assertThat(result.applied()).isFalse();
        assertThat(result.observedState()).isEqualTo("UNKNOWN");
        assertThat(result.observedRevision()).isZero();
        assertThat(result.message()).contains("未确认启用");
    }

    @Test
    void invalidActionOrSpecFailsBeforePlatformCall() {
        FakeClient client = new FakeClient();
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult actionFailure = adapter.apply(new PlatformControlIntent(
            "SUPERVISOR", "42", "APPROVE", 1L, validSpec()));
        PlatformApplyResult specFailure = adapter.apply(new PlatformControlIntent(
            "SUPERVISOR", "42", "ENSURE_RUNNING", 1L,
            new JobSpec("businessScheduleExecutor", "biz-1", "biz-1", "CRON", "0 */1 * * * ?",
                null, 60000L, 0, "{\"supervisorInstanceId\":42,\"batchLimit\":5,\"maxDurationMs\":5000}", null)));

        assertThat(actionFailure.applied()).isFalse();
        assertThat(specFailure.applied()).isFalse();
        assertThat(client.calls).isEmpty();
    }

    @Test
    void invalidScopeOrRevisionFailsBeforePlatformCall() {
        FakeClient client = new FakeClient();
        SnailJobSchedulerAdapter adapter = new SnailJobSchedulerAdapter(client);

        PlatformApplyResult scopeFailure = adapter.apply(new PlatformControlIntent(
            "", "42", "ENSURE_RUNNING", 1L, validSpec()));
        PlatformApplyResult revisionFailure = adapter.apply(new PlatformControlIntent(
            "SUPERVISOR", "42", "ENSURE_RUNNING", 0L, validSpec()));

        assertThat(scopeFailure.applied()).isFalse();
        assertThat(revisionFailure.applied()).isFalse();
        assertThat(client.calls).isEmpty();
    }

    private PlatformControlIntent intent(String action) {
        return new PlatformControlIntent("SUPERVISOR", "42", action, 7L, validSpec());
    }

    private JobSpec validSpec() {
        return new JobSpec("businessScheduleExecutor", "计划监督－入库－队列－越南",
            "iws:process:supervisor:42", "CRON", "0 */1 * * * ?", null, 60000L, null,
            "{\"supervisorInstanceId\":42,\"batchLimit\":5,\"maxDurationMs\":5000}", "DEFAULT");
    }

    private static final class FakeClient implements SnailJobOpenApiClient {

        private final List<String> calls = new ArrayList<>();
        private Long existingId;
        private Long idAfterAddFailure;
        private RuntimeException addFailure;
        private boolean statusResult = true;
        private boolean addAttempted;

        @Override
        public Optional<Long> findJobId(String bizId) {
            calls.add("find");
            if (existingId != null) {
                return Optional.of(existingId);
            }
            if (addAttempted && idAfterAddFailure != null) {
                existingId = idAfterAddFailure;
                idAfterAddFailure = null;
                return Optional.of(existingId);
            }
            return Optional.empty();
        }

        @Override
        public long add(SnailJobJobDefinition definition) {
            calls.add("add");
            addAttempted = true;
            if (addFailure != null) {
                throw addFailure;
            }
            existingId = 101L;
            return existingId;
        }

        @Override
        public boolean update(SnailJobJobDefinition definition) {
            calls.add("update");
            return true;
        }

        @Override
        public boolean updateRunning(String bizId, boolean running) {
            calls.add("status:" + running);
            return statusResult;
        }

        @Override
        public boolean trigger(String bizId) {
            calls.add("trigger");
            return true;
        }
    }
}
