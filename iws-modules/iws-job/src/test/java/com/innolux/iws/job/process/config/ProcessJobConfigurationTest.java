/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件 job 三开关装配测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.job.process.config;

import com.innolux.iws.job.process.executor.BusinessScheduleExecutor;
import com.innolux.iws.job.process.executor.PlanEventWorkerExecutor;
import com.innolux.iws.job.process.platform.SnailJobSchedulerAdapter;
import com.innolux.iws.process.api.ControlIntentSnapshot;
import com.innolux.iws.process.api.EventBatchResult;
import com.innolux.iws.process.api.PlatformResultAck;
import com.innolux.iws.process.api.PlatformResultCommand;
import com.innolux.iws.process.api.ProcessSchedulerPort;
import com.innolux.iws.process.api.SupervisorTickResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个开关全部开启才注册计划事件执行器与控制适配器。
 */
@Tag("dev")
class ProcessJobConfigurationTest {

    @Test
    void allThreeSwitchesEnableWiring() {
        try (AnnotationConfigApplicationContext context = context(true, true, true)) {
            assertThat(context.getBeanNamesForType(BusinessScheduleExecutor.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(PlanEventWorkerExecutor.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(SnailJobSchedulerAdapter.class)).hasSize(1);
        }
    }

    @Test
    void processSwitchOffDisablesWiring() {
        assertNoWiring(context(false, true, true));
    }

    @Test
    void schedulerSwitchOffDisablesWiring() {
        assertNoWiring(context(true, false, true));
    }

    @Test
    void snailJobSwitchOffDisablesWiring() {
        assertNoWiring(context(true, true, false));
    }

    private void assertNoWiring(AnnotationConfigApplicationContext context) {
        try (context) {
            assertThat(context.getBeanNamesForType(BusinessScheduleExecutor.class)).isEmpty();
            assertThat(context.getBeanNamesForType(PlanEventWorkerExecutor.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SnailJobSchedulerAdapter.class)).isEmpty();
        }
    }

    private AnnotationConfigApplicationContext context(boolean process, boolean scheduler, boolean snailJob) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "iws.process.enabled", Boolean.toString(process),
            "iws.process.scheduler.enabled", Boolean.toString(scheduler),
            "snail-job.enabled", Boolean.toString(snailJob))));
        context.registerBean(ProcessSchedulerPort.class, NoopPort::new);
        context.register(ProcessJobConfiguration.class);
        context.refresh();
        return context;
    }

    private static final class NoopPort implements ProcessSchedulerPort {

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
            return null;
        }

        @Override
        public PlatformResultAck recordPlatformResult(PlatformResultCommand command) {
            return null;
        }
    }
}
