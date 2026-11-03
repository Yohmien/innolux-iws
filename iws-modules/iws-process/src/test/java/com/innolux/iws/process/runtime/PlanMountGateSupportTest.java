/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 验证挂载门禁按业务类型筛选并阻断后续执行；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.domain.BusinessSchedule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 计划挂载门禁执行测试。 */
@Tag("local")
class PlanMountGateSupportTest {

    @Test
    void runsOnlyMatchingGatesInOrder() {
        PlanMountGate first = mock(PlanMountGate.class);
        PlanMountGate skipped = mock(PlanMountGate.class);
        PlanMountGate last = mock(PlanMountGate.class);
        when(first.supports("INBOUND_FIXTURE")).thenReturn(true);
        when(skipped.supports("INBOUND_FIXTURE")).thenReturn(false);
        when(last.supports("INBOUND_FIXTURE")).thenReturn(true);

        PlanMountGateSupport.requireAllowed(
            List.of(first, skipped, last), command(), schedule());

        var order = inOrder(first, last);
        order.verify(first).requireMountAllowed(command(), schedule());
        order.verify(last).requireMountAllowed(command(), schedule());
        verify(skipped, never()).requireMountAllowed(command(), schedule());
    }

    @Test
    void rejectionStopsLaterGates() {
        PlanMountGate rejected = mock(PlanMountGate.class);
        PlanMountGate later = mock(PlanMountGate.class);
        when(rejected.supports("INBOUND_FIXTURE")).thenReturn(true);
        when(later.supports("INBOUND_FIXTURE")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("MOUNT_NOT_ALLOWED"))
            .when(rejected).requireMountAllowed(command(), schedule());

        assertThatThrownBy(() -> PlanMountGateSupport.requireAllowed(
            List.of(rejected, later), command(), schedule()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("MOUNT_NOT_ALLOWED");

        verify(later, never()).requireMountAllowed(command(), schedule());
    }

    private AttachTaskCommand command() {
        return new AttachTaskCommand(
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
    }

    private BusinessSchedule schedule() {
        BusinessSchedule schedule = new BusinessSchedule();
        schedule.setId(10L);
        schedule.setBusinessCode("INBOUND");
        schedule.setEnabled(true);
        return schedule;
    }
}
