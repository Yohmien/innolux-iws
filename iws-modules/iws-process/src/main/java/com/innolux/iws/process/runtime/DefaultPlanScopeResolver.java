/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 默认受控 scope；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 无业务 scope 规则时使用受控 DEFAULT。
 */
@Component
@Primary
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class DefaultPlanScopeResolver implements PlanScopeResolver {

    /**
     * {@inheritDoc}
     */
    @Override
    public String resolve(String businessType, String planType, String businessId, String taskKey) {
        return "DEFAULT";
    }
}
