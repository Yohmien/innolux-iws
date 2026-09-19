/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import com.innolux.iws.common.core.validate.AddGroup;

/**
 * 流程变量参数
 *
 * @param instanceId 流程实例 ID
 * @param key        变量键
 * @param value      变量值
 * @author YONGJIE.PENG
 */
public record FlowVariableBo(
    @NotNull(message = "流程实例id为空", groups = AddGroup.class)
    Long instanceId,
    @NotNull(message = "流程变量key为空", groups = AddGroup.class)
    String key,
    @NotNull(message = "流程变量value为空", groups = AddGroup.class)
    String value
) {
}
