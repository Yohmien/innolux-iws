/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 两个计划事件固定执行器的参数与失败边界测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.executor;

import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.innolux.iws.job.process.control.SupervisorControlService;
import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.ProcessSchedulerPort;
import com.innolux.iws.process.api.SupervisorTickResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行器只将校验后的 scope 与 budget 传给 process。
 */
@Tag("dev")
class ProcessJobExecutorsTest {

    @Test
    void businessScheduleExecutorPassesValidatedScopeAndBudget() {
        FakePort port = new FakePort();
        FakeControl control = new FakeControl();
        BusinessScheduleExecutor executor = new BusinessScheduleExecutor(port, control);
        JobArgs jobArgs = args(Map.of(
            "supervisorInstanceId", "42", "batchLimit", 6, "maxDurationMs", 3000L));

        ExecuteResult result = executor.jobExecute(jobArgs);

        assertThat(port.supervisorId).isEqualTo(42L);
        assertThat(port.batchLimit).isEqualTo(6);
        assertThat(port.maxDurationMs).isEqualTo(3000L);
        assertThat(control.supervisorId).isEqualTo(42L);
        assertThat(result.getResult()).isInstanceOf(SupervisorTickResult.class);
    }

    @Test
    void planEventWorkerExecutorAcceptsJsonAndPassesValidatedScopeAndBudget() {
        FakePort port = new FakePort();
        PlanEventWorkerExecutor executor = new PlanEventWorkerExecutor(port);
        JobArgs jobArgs = args("{\"scheduleId\":\"9\",\"batchLimit\":\"4\",\"maxDurationMs\":\"2500\"}");

        ExecuteResult result = executor.jobExecute(jobArgs);

        assertThat(port.scheduleId).isEqualTo(9L);
        assertThat(port.batchLimit).isEqualTo(4);
        assertThat(port.maxDurationMs).isEqualTo(2500L);
        assertThat(result.getResult()).isInstanceOf(EventBatchResult.class);
    }

    @Test
    void missingBudgetIsFailureAndProcessIsNotCalled() {
        FakePort port = new FakePort();
        PlanEventWorkerExecutor executor = new PlanEventWorkerExecutor(port);

        ExecuteResult result = executor.jobExecute(args(Map.of("scheduleId", 9, "batchLimit", 4)));

        assertThat(result.getResult()).isNull();
        assertThat(result.getMessage()).contains("maxDurationMs");
        assertThat(port.called).isFalse();
    }

    @Test
    void wrongScopeKeyIsFailureAndProcessIsNotCalled() {
        FakePort port = new FakePort();
        BusinessScheduleExecutor executor = new BusinessScheduleExecutor(port, new FakeControl());

        ExecuteResult result = executor.jobExecute(args(Map.of(
            "scheduleId", 9, "batchLimit", 4, "maxDurationMs", 2500)));

        assertThat(result.getResult()).isNull();
        assertThat(result.getMessage()).contains("supervisorInstanceId");
        assertThat(port.called).isFalse();
    }

    @Test
    void processFailureIsReturnedAsFailure() {
        FakePort port = new FakePort();
        port.failure = new IllegalStateException("process unavailable");
        PlanEventWorkerExecutor executor = new PlanEventWorkerExecutor(port);

        ExecuteResult result = executor.jobExecute(args(Map.of(
            "scheduleId", 9, "batchLimit", 4, "maxDurationMs", 2500)));

        assertThat(result.getResult()).isNull();
        assertThat(result.getMessage()).contains("process unavailable");
    }

    private JobArgs args(Object params) {
        JobArgs jobArgs = new JobArgs();
        jobArgs.setJobParams(params);
        return jobArgs;
    }

    private static final class FakePort implements ProcessSchedulerPort {

        private long supervisorId;
        private long scheduleId;
        private int batchLimit;
        private long maxDurationMs;
        private boolean called;
        private RuntimeException failure;

        @Override
        public SupervisorTickResult tickSupervisor(long supervisorInstanceId, int batchLimit, long maxDurationMs) {
            failIfNeeded();
            this.called = true;
            this.supervisorId = supervisorInstanceId;
            this.batchLimit = batchLimit;
            this.maxDurationMs = maxDurationMs;
            return new SupervisorTickResult(supervisorInstanceId, 1L, "COMPLETED", 1, 1, 1, 10L, false, null);
        }

        @Override
        public EventBatchResult runEventBatch(long scheduleId, int batchLimit, long maxDurationMs) {
            failIfNeeded();
            this.called = true;
            this.scheduleId = scheduleId;
            this.batchLimit = batchLimit;
            this.maxDurationMs = maxDurationMs;
            return new EventBatchResult(scheduleId, 1, 1, 0, 0, false, 10L, null);
        }

        @Override
        public ControlIntentSnapshot flushControlIntent(long supervisorInstanceId) {
            return null;
        }

        @Override
        public PlatformResultAck recordPlatformResult(PlatformResultCommand command) {
            return null;
        }

        private void failIfNeeded() {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class FakeControl extends SupervisorControlService {

        private long supervisorId;

        private FakeControl() {
            super(null, null);
        }

        @Override
        public void synchronize(long supervisorInstanceId) {
            this.supervisorId = supervisorInstanceId;
        }
    }
}
