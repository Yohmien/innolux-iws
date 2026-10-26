/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布计划对象范围授权；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.runtime.ProcessAccessPolicy;
import com.innolux.iws.process.runtime.ProcessAccessTarget;
import com.innolux.iws.process.service.BusinessScheduleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 只放行 APK_RELEASE 业务调度下的 APK 发布对象。
 *
 * <p>功能权限与对象范围同时满足才放行；调度编码必须从当前数据库读取，
 * 不能仅凭请求中的 businessType 自报身份。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ApkReleaseProcessAccessPolicy implements ProcessAccessPolicy {

    private final ObjectProvider<BusinessScheduleService> scheduleProvider;

    /**
     * 构造对象范围策略。
     *
     * @param scheduleProvider 业务调度服务
     */
    public ApkReleaseProcessAccessPolicy(ObjectProvider<BusinessScheduleService> scheduleProvider) {
        this.scheduleProvider = scheduleProvider;
    }

    /**
     * 判断当前用户是否可访问目标对象。
     *
     * @param target 授权目标
     * @return true 表示允许
     */
    @Override
    public boolean canAccess(ProcessAccessTarget target) {
        if (!StpUtil.isLogin()) {
            return false;
        }
        boolean permitted = ApkProcessContract.ACCESS_PERMISSIONS.stream().anyMatch(StpUtil::hasPermission);
        return permits(target, scheduleCode(target), permitted);
    }

    private String scheduleCode(ProcessAccessTarget target) {
        if (target == null || target.scheduleId() == null || target.scheduleId() <= 0) {
            return null;
        }
        BusinessScheduleService scheduleService = scheduleProvider.getIfAvailable();
        if (scheduleService == null) {
            return null;
        }
        try {
            BusinessScheduleVo schedule = scheduleService.get(target.scheduleId());
            return schedule == null ? null : schedule.businessCode();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 纯范围判定，便于独立验证；调度编码必须来自数据库事实。
     *
     * @param target 授权目标
     * @param scheduleBusinessCode 目标调度的业务编码
     * @param permitted 是否具有 APK 功能权限
     * @return true 表示允许
     */
    static boolean permits(ProcessAccessTarget target, String scheduleBusinessCode, boolean permitted) {
        if (!permitted || target == null
            || !ApkProcessContract.BUSINESS_CODE.equals(scheduleBusinessCode)) {
            return false;
        }
        if (target.businessType() != null && !ApkProcessContract.BUSINESS_TYPE.equals(target.businessType())) {
            return false;
        }
        if (target.businessId() != null && !positiveLong(target.businessId())) {
            return false;
        }
        if (target.taskKey() != null && !target.taskKey().equals(target.businessId())) {
            return false;
        }
        return target.scheduleScope() == null || target.scheduleScope().isBlank()
            || "DEFAULT".equals(target.scheduleScope());
    }

    private static boolean positiveLong(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
