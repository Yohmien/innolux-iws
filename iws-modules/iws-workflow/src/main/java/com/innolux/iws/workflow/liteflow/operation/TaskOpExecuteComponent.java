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
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.core.service.TaskService;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.common.enums.TaskStatusEnum;
import com.innolux.iws.workflow.domain.context.TaskOperationContext;

/**
 * 执行委派、转办、加签或减签。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("taskOpExecute")
public class TaskOpExecuteComponent extends NodeComponent {

    private final TaskService taskService;

    @Override
    public void process() {
        TaskOperationContext context = getContextBean(TaskOperationContext.class);
        Long taskId = context.getTaskOperationBo().getTaskId();
        boolean result = false;
        switch (context.getOperation()) {
            case DELEGATE_TASK -> {
                context.getFlowParams().hisStatus(TaskStatusEnum.DEPUTE.getStatus());
                result = taskService.depute(taskId, context.getFlowParams());
            }
            case TRANSFER_TASK -> {
                context.getFlowParams().hisStatus(TaskStatusEnum.TRANSFER.getStatus());
                result = taskService.transfer(taskId, context.getFlowParams());
            }
            case ADD_SIGNATURE -> {
                context.getFlowParams().hisStatus(TaskStatusEnum.SIGN.getStatus());
                result = taskService.addSignature(taskId, context.getFlowParams());
            }
            case REDUCTION_SIGNATURE -> {
                context.getFlowParams().hisStatus(TaskStatusEnum.SIGN_OFF.getStatus());
                result = taskService.reductionSignature(taskId, context.getFlowParams());
            }
        }
        context.setResult(result);
    }

}
