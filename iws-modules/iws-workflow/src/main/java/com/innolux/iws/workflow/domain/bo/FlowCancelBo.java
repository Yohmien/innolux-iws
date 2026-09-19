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

import jakarta.validation.constraints.NotBlank;
import com.innolux.iws.common.core.validate.AddGroup;

/**
 * 撤销流程请求对象。
 *
 * @param businessId 业务 ID
 * @param message    撤销说明
 * @author YONGJIE.PENG
 */
public record FlowCancelBo(
    @NotBlank(message = "业务ID不能为空", groups = AddGroup.class)
    String businessId,
    String message
) {
}
