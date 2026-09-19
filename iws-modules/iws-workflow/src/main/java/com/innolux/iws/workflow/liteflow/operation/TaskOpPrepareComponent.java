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

package com.innolux.iws.workflow.liteflow.operation;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.core.utils.ValidatorUtils;
import com.innolux.iws.common.core.validate.AddGroup;
import com.innolux.iws.common.core.validate.EditGroup;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import org.dromara.warm.flow.core.dto.FlowParams;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.common.enums.TaskOperationEnum;
import com.innolux.iws.workflow.domain.bo.TaskOperationBo;
import com.innolux.iws.workflow.domain.context.TaskOperationContext;

import java.util.Collections;

/**
 * 准备任务操作参数。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@Slf4j
@LiteflowComponent("taskOpPrepare")
public class TaskOpPrepareComponent extends NodeComponent {

    @Override
    public void process() {
        TaskOperationContext context = getContextBean(TaskOperationContext.class);
        TaskOperationEnum op = TaskOperationEnum.getByCode(context.getTaskOperation());
        if (op == null) {
            log.error("Invalid operation type:{} ", context.getTaskOperation());
            throw new ServiceException("Invalid operation type " + context.getTaskOperation());
        }
        context.setOperation(op);

        TaskOperationBo bo = context.getTaskOperationBo();
        switch (op) {
            case DELEGATE_TASK, TRANSFER_TASK -> ValidatorUtils.validate(bo, AddGroup.class);
            case ADD_SIGNATURE, REDUCTION_SIGNATURE -> ValidatorUtils.validate(bo, EditGroup.class);
        }

        FlowParams flowParams = FlowParams.build().message(bo.getMessage());
        if (LoginHelper.isSuperAdmin()) {
            flowParams.ignore(true);
        }
        switch (op) {
            case DELEGATE_TASK, TRANSFER_TASK -> flowParams.addHandlers(Collections.singletonList(bo.getUserId()));
            case ADD_SIGNATURE -> flowParams.addHandlers(bo.getUserIds());
            case REDUCTION_SIGNATURE -> flowParams.reductionHandlers(bo.getUserIds());
        }
        context.setFlowParams(flowParams);
    }

}
