package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.JobSpec;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("local")
class SupervisorJobSpecFactoryTest {

    private static final Pattern SNAIL_JOB_BIZ_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Test
    void supervisorBizIdIsStableAndAcceptedBySnailJob() {
        ProcessInstance supervisor = mock(ProcessInstance.class);
        PlanDefinition definition = mock(PlanDefinition.class);
        BusinessSchedule schedule = mock(BusinessSchedule.class);
        BusinessSchedulePlanType planType = mock(BusinessSchedulePlanType.class);
        when(supervisor.getId()).thenReturn(2117541987524845570L);
        when(supervisor.getScheduleScope()).thenReturn("DEFAULT");
        when(definition.getScheduleIntervalMs()).thenReturn(1000L);
        when(definition.getRoundMaxDurationMs()).thenReturn(1000L);
        when(definition.getJobTimeoutMs()).thenReturn(1000L);
        when(schedule.getBusinessName()).thenReturn("空间变更");
        when(planType.getPlanTypeName()).thenReturn("空间变更");

        JobSpec spec = SupervisorJobSpecFactory.supervisor(supervisor, definition, schedule, planType);

        assertThat(spec.bizId()).isEqualTo("process_supervisor_2117541987524845570");
        assertThat(spec.bizId()).matches(SNAIL_JOB_BIZ_ID);
    }

    @Test
    void workerBizIdIsStableAndAcceptedBySnailJob() {
        BusinessSchedule schedule = mock(BusinessSchedule.class);
        PlanDefinition definition = mock(PlanDefinition.class);
        when(schedule.getId()).thenReturn(1761400000000060002L);
        when(schedule.getBusinessName()).thenReturn("空间变更");
        when(definition.getWorkerBatchLimit()).thenReturn(10);
        when(definition.getWorkerMaxDurationMs()).thenReturn(1000L);
        when(definition.getScheduleIntervalMs()).thenReturn(1000L);
        when(definition.getJobTimeoutMs()).thenReturn(1000L);

        JobSpec spec = SupervisorJobSpecFactory.worker(schedule, definition);

        assertThat(spec.bizId()).isEqualTo("process_worker_1761400000000060002");
        assertThat(spec.bizId()).matches(SNAIL_JOB_BIZ_ID);
    }
}
