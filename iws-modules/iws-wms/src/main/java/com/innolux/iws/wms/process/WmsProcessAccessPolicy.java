/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划对象授权
 * @Version      : V1.0
 * @Description  : 将 WMS 调度、业务实例和公司范围接入计划对象授权；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.process;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.process.runtime.ProcessAccessPolicy;
import com.innolux.iws.process.runtime.ProcessAccessTarget;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergePlanContract;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeStartService;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.process.WmsRulePlanContract;
import com.innolux.iws.wms.space.change.service.WmsSpaceChangeService;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WMS 计划对象授权策略。
 *
 * <p>功能权限从数据库当前角色解析；空间业务再校验目标公司范围。挂载阶段只按
 * 调度和功能权限准入，具体业务公司由 WMS 挂载门禁在当前事务中复核。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class WmsProcessAccessPolicy implements ProcessAccessPolicy {

    private static final Map<String, Route> SCHEDULE_ROUTES = Map.of(
        WmsSpaceChangeService.SCHEDULE_CODE,
        new Route(WmsSpaceChangeService.PERMISSION_CHANGE),
        WmsRulePlanContract.BUSINESS_CODE,
        new Route(WmsRuleContract.PERMISSION_PUBLISH),
        WmsLogicalMergePlanContract.SCHEDULE_CODE,
        new Route(WmsLogicalMergeStartService.PERMISSION_MANAGE)
    );

    private final JdbcTemplate jdbcTemplate;

    private final WmsCompanyAccessPolicy accessPolicy;

    @Autowired
    public WmsProcessAccessPolicy(
        JdbcTemplate jdbcTemplate,
        WmsCompanyAccessPolicy accessPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public boolean canAccess(ProcessAccessTarget target) {
        if (!StpUtil.isLogin() || target == null) {
            return false;
        }
        Facts facts = facts(target);
        Route route = facts.scheduleCode() == null
            ? null : SCHEDULE_ROUTES.get(facts.scheduleCode());
        if (route == null || !accessPolicy.hasPermission(route.permission())) {
            return false;
        }
        if (!businessTypeAllowed(facts.businessType(), target.businessType())) {
            return false;
        }
        if ("ATTACH".equals(target.action())) {
            return true;
        }
        return companyAccessible(target, facts);
    }

    private Facts facts(ProcessAccessTarget target) {
        if (target.instanceId() > 0) {
            return jdbcTemplate.query("""
                select business_schedule_code, business_type, plan_type, schedule_scope
                  from iws_process_instance where id = ?
                """, (rs, rowNum) -> new Facts(
                rs.getString("business_schedule_code"),
                rs.getString("business_type"),
                rs.getString("plan_type"),
                rs.getString("schedule_scope")
            ), target.instanceId()).stream().findFirst()
                .orElse(new Facts(null, null, null, null));
        }
        if (target.scheduleId() != null && target.scheduleId() > 0) {
            return jdbcTemplate.query("""
                select business_code from iws_business_schedule where id = ?
                """, (rs, rowNum) -> new Facts(
                rs.getString("business_code"), null, null, null
            ), target.scheduleId()).stream().findFirst()
                .orElse(new Facts(null, null, null, null));
        }
        return new Facts(null, null, null, null);
    }

    private boolean businessTypeAllowed(String instanceType, String targetType) {
        if (instanceType != null
            && !WmsSpaceChangeService.BUSINESS_TYPE.equals(instanceType)
            && !WmsRulePlanContract.BUSINESS_TYPE.equals(instanceType)
            && !WmsLogicalMergePlanContract.BUSINESS_TYPE.equals(instanceType)) {
            return false;
        }
        if (targetType == null) {
            return true;
        }
        return WmsSpaceChangeService.BUSINESS_TYPE.equals(targetType)
            || WmsSpaceChangeService.PLAN_TYPE_CHANGE.equals(targetType)
            || WmsSpaceChangeService.PLAN_TYPE_RETIRE.equals(targetType)
            || WmsRulePlanContract.BUSINESS_TYPE.equals(targetType)
            || WmsLogicalMergePlanContract.BUSINESS_TYPE.equals(targetType)
            || WmsLogicalMergePlanContract.BATCH_PLAN_TYPE.equals(targetType)
            || WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE.equals(targetType);
    }

    private boolean companyAccessible(ProcessAccessTarget target, Facts facts) {
        if (!WmsSpaceChangeService.BUSINESS_TYPE.equals(facts.businessType())
            || target.businessId() == null) {
            Long scopedCompanyId = scopeCompanyId(facts.scheduleScope());
            if (scopedCompanyId != null) {
                return accessPolicy.hasCompanyAccess(scopedCompanyId);
            }
            // 规则包和合并父单的对象范围由各自持久业务单在执行事务内继续校验。
            return true;
        }
        Long orderId = positiveLong(target.businessId());
        if (orderId == null) {
            return false;
        }
        Long companyId = WmsSpaceOrderCompanyResolver.resolve(jdbcTemplate, orderId);
        Long scopedCompanyId = scopeCompanyId(facts.scheduleScope());
        return companyId != null
            && (scopedCompanyId == null || scopedCompanyId.equals(companyId))
            && accessPolicy.hasCompanyAccess(companyId);
    }

    private static Long scopeCompanyId(String scope) {
        if (scope == null || !scope.startsWith("COMPANY:")) {
            return null;
        }
        return positiveLong(scope.substring("COMPANY:".length()));
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private record Route(String permission) {
    }

    private record Facts(
        String scheduleCode,
        String businessType,
        String planType,
        String scheduleScope
    ) {
    }
}
