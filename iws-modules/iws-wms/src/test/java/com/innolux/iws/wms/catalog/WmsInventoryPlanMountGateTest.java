/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 验证 WMS 只为已登记业务类型启用挂载目录门禁；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.catalog;

import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.SchedulerPlatformPort;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.wms.catalog.domain.WmsBusinessTypeRow;
import com.innolux.iws.wms.catalog.mapper.WmsInventoryCatalogMapper;
import com.innolux.iws.wms.catalog.service.WmsInventoryCatalogService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WMS 计划挂载门禁测试。 */
@Tag("local")
class WmsInventoryPlanMountGateTest {

    private final WmsInventoryCatalogMapper catalogMapper =
        mock(WmsInventoryCatalogMapper.class);

    private final WmsInventoryCatalogService catalogService =
        mock(WmsInventoryCatalogService.class);

    private final ProcessProperties processProperties = new ProcessProperties();

    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<SchedulerPlatformPort>
        schedulerPlatformPorts = mock(org.springframework.beans.factory.ObjectProvider.class);

    private final WmsInventoryPlanMountGate gate =
        new WmsInventoryPlanMountGate(
            catalogMapper,
            catalogService,
            processProperties,
            schedulerPlatformPorts
        );

    @Test
    void ignoresBusinessTypesNotRegisteredInWmsCatalog() {
        assertThat(gate.supports(null)).isFalse();
        assertThat(gate.supports("OTHER_MODULE")).isFalse();
        verify(catalogService, never()).requireMountAllowed(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void delegatesRegisteredBusinessTypeWithTrustedScheduleCode() {
        WmsBusinessTypeRow row = new WmsBusinessTypeRow();
        row.setBusinessTypeCode("INBOUND_FIXTURE");
        when(catalogMapper.selectBusinessType("INBOUND_FIXTURE")).thenReturn(row);
        processProperties.getScheduler().setEnabled(true);
        when(schedulerPlatformPorts.getIfAvailable())
            .thenReturn(mock(SchedulerPlatformPort.class));
        AttachTaskCommand command = command();
        BusinessSchedule schedule = schedule();

        assertThat(gate.supports("INBOUND_FIXTURE")).isTrue();
        gate.requireMountAllowed(command, schedule);

        verify(catalogService).requireMountAllowed(
            "INBOUND_FIXTURE",
            "PARENT_PLAN",
            "INBOUND",
            WmsInventoryCatalogContract.CONTRACT_VERSION
        );
    }

    @Test
    void rejectsQueueRuntimeWhenSchedulerIsDisabledOrPlatformPortIsAbsent() {
        WmsBusinessTypeRow row = new WmsBusinessTypeRow();
        row.setBusinessTypeCode("INBOUND_FIXTURE");
        when(catalogMapper.selectBusinessType("INBOUND_FIXTURE")).thenReturn(row);
        AttachTaskCommand command = command();
        BusinessSchedule schedule = schedule();

        assertThatThrownBy(() -> gate.requireMountAllowed(command, schedule))
            .hasMessageContaining("QUEUE_RUNTIME_UNAVAILABLE");

        processProperties.getScheduler().setEnabled(true);
        when(schedulerPlatformPorts.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> gate.requireMountAllowed(command, schedule))
            .hasMessageContaining("QUEUE_RUNTIME_UNAVAILABLE");
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
