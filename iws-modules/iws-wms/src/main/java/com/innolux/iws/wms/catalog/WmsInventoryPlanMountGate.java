/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 在计划实例挂载前执行 WMS 业务类型与计划挂载准入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.catalog;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.SchedulerPlatformPort;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.runtime.PlanMountGate;
import com.innolux.iws.wms.catalog.mapper.WmsInventoryCatalogMapper;
import com.innolux.iws.wms.catalog.service.WmsInventoryCatalogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** WMS 计划挂载目录门禁。 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsInventoryPlanMountGate implements PlanMountGate {

    private final WmsInventoryCatalogMapper catalogMapper;

    private final WmsInventoryCatalogService catalogService;

    private final ProcessProperties processProperties;

    private final ObjectProvider<SchedulerPlatformPort> schedulerPlatformPorts;

    public WmsInventoryPlanMountGate(
        WmsInventoryCatalogMapper catalogMapper,
        WmsInventoryCatalogService catalogService,
        ProcessProperties processProperties,
        ObjectProvider<SchedulerPlatformPort> schedulerPlatformPorts
    ) {
        this.catalogMapper = catalogMapper;
        this.catalogService = catalogService;
        this.processProperties = processProperties;
        this.schedulerPlatformPorts = schedulerPlatformPorts;
    }

    @Override
    public boolean supports(String businessType) {
        return businessType != null
            && catalogMapper.selectBusinessType(businessType) != null;
    }

    @Override
    public void requireMountAllowed(AttachTaskCommand command, BusinessSchedule schedule) {
        catalogService.requireMountAllowed(
            command.businessType(),
            command.planType(),
            schedule.getBusinessCode(),
            WmsInventoryCatalogContract.CONTRACT_VERSION
        );
        if (!processProperties.getScheduler().isEnabled()
            || schedulerPlatformPorts.getIfAvailable() == null) {
            throw new ServiceException(
                "QUEUE_RUNTIME_UNAVAILABLE: process.scheduler 与 snail-job 必须显式启用"
            );
        }
    }
}
