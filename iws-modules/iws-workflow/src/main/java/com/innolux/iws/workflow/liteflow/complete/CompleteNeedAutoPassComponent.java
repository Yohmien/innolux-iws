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

package com.innolux.iws.workflow.liteflow.complete;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeBooleanComponent;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.context.CompleteTaskContext;

/**
 * 判断办理任务后是否进入自动审批分支。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@LiteflowComponent("completeNeedAutoPass")
public class CompleteNeedAutoPassComponent extends NodeBooleanComponent {

    @Override
    public boolean processBoolean() {
        CompleteTaskContext context = getContextBean(CompleteTaskContext.class);
        return Boolean.TRUE.equals(context.getAutoPass());
    }

}
