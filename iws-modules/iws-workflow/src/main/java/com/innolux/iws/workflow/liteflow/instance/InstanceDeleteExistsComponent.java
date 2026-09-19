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

package com.innolux.iws.workflow.liteflow.instance;

import cn.hutool.core.collection.CollUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeBooleanComponent;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.context.InstanceDeleteContext;

/**
 * 判断删除流程实例时是否存在可执行删除的数据。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@LiteflowComponent("instanceDeleteExists")
public class InstanceDeleteExistsComponent extends NodeBooleanComponent {

    @Override
    public boolean processBoolean() {
        InstanceDeleteContext context = getContextBean(InstanceDeleteContext.class);
        return CollUtil.isNotEmpty(context.getFlowInstances());
    }

}
