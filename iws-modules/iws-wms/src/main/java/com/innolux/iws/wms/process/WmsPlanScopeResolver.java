/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划对象授权
 * @Version      : V1.0
 * @Description  : 按持久空间业务单解析公司级计划监督范围；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.process;

import com.innolux.iws.process.runtime.PlanScopeResolver;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * WMS 计划范围解析器。
 *
 * <p>空间变更单在挂载前已经持久化，使用其公司身份隔离监督实例；其他 WMS 计划
 * 保留受控 DEFAULT，具体业务对象授权仍由执行事务继续校验。</p>
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsPlanScopeResolver implements PlanScopeResolver {

    private final JdbcTemplate jdbcTemplate;

    public WmsPlanScopeResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String resolve(
        String businessType,
        String planType,
        String businessId,
        String taskKey
    ) {
        if (!WmsSpaceChangeService.BUSINESS_TYPE.equals(businessType)) {
            return "DEFAULT";
        }
        Long orderId = positiveLong(businessId);
        Long companyId = WmsSpaceOrderCompanyResolver.resolve(jdbcTemplate, orderId);
        return companyId == null ? "DEFAULT" : "COMPANY:" + companyId;
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException error) {
            return null;
        }
    }
}
