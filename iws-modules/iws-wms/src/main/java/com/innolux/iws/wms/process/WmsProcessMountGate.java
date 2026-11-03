/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划对象授权
 * @Version      : V1.0
 * @Description  : 在 WMS 空间计划挂载前复核目标公司范围；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.runtime.PlanMountGate;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * WMS 挂载门禁。
 *
 * <p>空间变更单在挂载前已经持久化，故可用业务 ID 取公司并执行数据库当前授权；
 * 该检查与功能权限、对象策略分别生效，查询权限不能替代变更权限。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsProcessMountGate implements PlanMountGate {

    private final JdbcTemplate jdbcTemplate;

    private final WmsCompanyAccessPolicy accessPolicy;

    public WmsProcessMountGate(
        JdbcTemplate jdbcTemplate,
        WmsCompanyAccessPolicy accessPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public boolean supports(String businessType) {
        return WmsSpaceChangeService.BUSINESS_TYPE.equals(businessType);
    }

    @Override
    public void requireMountAllowed(AttachTaskCommand command, BusinessSchedule schedule) {
        accessPolicy.checkPermissionForShare(WmsSpaceChangeService.PERMISSION_CHANGE);
        Long orderId = positiveLong(command.businessId());
        Long companyId = WmsSpaceOrderCompanyResolver.resolve(jdbcTemplate, orderId);
        if (companyId == null) {
            throw new ServiceException("空间变更单缺少可授权公司范围");
        }
        accessPolicy.lockAndCheckCompanyForShare(companyId);
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

}
