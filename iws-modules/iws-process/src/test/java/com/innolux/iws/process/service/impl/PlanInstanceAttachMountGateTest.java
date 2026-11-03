/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 验证挂载门禁在计划类型与实例写入前执行；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.process.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessRequestDetailMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.runtime.EventEligibilityService;
import com.innolux.iws.process.runtime.InboundIdentityPolicy;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.PlanInstanceActionPolicy;
import com.innolux.iws.process.runtime.PlanMaterializer;
import com.innolux.iws.process.runtime.PlanMountGate;
import com.innolux.iws.process.runtime.PlanScopeResolver;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.SupervisorRuntimeService;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.ProcessAuditService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 计划实例挂载门禁接线测试。 */
@Tag("local")
class PlanInstanceAttachMountGateTest {

    @Test
    void rejectsBeforePlanTypeLookupOrInstanceInsert() {
        BusinessScheduleMapper scheduleMapper = mock(BusinessScheduleMapper.class);
        BusinessSchedulePlanTypeMapper planTypeMapper =
            mock(BusinessSchedulePlanTypeMapper.class);
        ProcessInstanceMapper instanceMapper = mock(ProcessInstanceMapper.class);
        PlanMountGate gate = mock(PlanMountGate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PlanMountGate> gates = mock(ObjectProvider.class);
        BusinessSchedule schedule = new BusinessSchedule();
        schedule.setId(10L);
        schedule.setBusinessCode("INBOUND");
        schedule.setEnabled(true);
        AttachTaskCommand command = new AttachTaskCommand(
            10L,
            "PARENT_PLAN",
            "INBOUND_FIXTURE",
            "88001",
            "TASK-1",
            false,
            "a".repeat(64),
            List.of(),
            "CMD-1"
        );
        when(scheduleMapper.selectById(10L)).thenReturn(schedule);
        when(gate.supports("INBOUND_FIXTURE")).thenReturn(true);
        when(gates.orderedStream()).thenReturn(Stream.of(gate));
        doThrow(new ServiceException("MOUNT_NOT_ALLOWED"))
            .when(gate).requireMountAllowed(command, schedule);
        PlanInstanceServiceImpl service = service(
            scheduleMapper, planTypeMapper, instanceMapper, gates);

        assertThatThrownBy(() -> service.attach(command))
            .isInstanceOf(ServiceException.class)
            .hasMessage("MOUNT_NOT_ALLOWED");

        verify(gate).requireMountAllowed(command, schedule);
        verify(planTypeMapper, never()).selectOne(any(Wrapper.class));
        verify(instanceMapper, never()).insert(any(ProcessInstance.class));
    }

    private PlanInstanceServiceImpl service(
        BusinessScheduleMapper scheduleMapper,
        BusinessSchedulePlanTypeMapper planTypeMapper,
        ProcessInstanceMapper instanceMapper,
        ObjectProvider<PlanMountGate> gates
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<InboundIdentityPolicy> inbound =
            mock(ObjectProvider.class);
        return new PlanInstanceServiceImpl(
            scheduleMapper,
            planTypeMapper,
            mock(PlanDefinitionMapper.class),
            instanceMapper,
            mock(ProcessSystemContextMapper.class),
            mock(ProcessEventMapper.class),
            mock(ProcessTokenMapper.class),
            mock(ProcessAttemptMapper.class),
            mock(ProcessTriggerReceiptMapper.class),
            mock(ProcessScheduleControlMapper.class),
            mock(RuntimeDao.class),
            mock(PlanMaterializer.class),
            mock(SupervisorRuntimeService.class),
            mock(EventEligibilityService.class),
            mock(PlanActivationService.class),
            mock(ProcessAuditService.class),
            mock(CommandReceiptService.class),
            mock(PlanScopeResolver.class),
            mock(ProcessObjectAuthorizer.class),
            mock(PlanInstanceActionPolicy.class),
            mock(ProcessRequestDetailMapper.class),
            inbound,
            gates
        );
    }
}
