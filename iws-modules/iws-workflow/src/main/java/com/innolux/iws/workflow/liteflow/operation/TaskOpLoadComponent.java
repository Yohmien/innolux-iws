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

import cn.hutool.core.util.ObjectUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.query.QueryBuilder;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.enums.CooperateType;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.orm.entity.FlowNode;
import org.dromara.warm.flow.orm.mapper.FlowNodeMapper;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.common.enums.TaskOperationEnum;
import com.innolux.iws.workflow.domain.context.TaskOperationContext;

/**
 * 加载任务节点并校验操作约束。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("taskOpLoad")
public class TaskOpLoadComponent extends NodeComponent {

    private final TaskService taskService;
    private final FlowNodeMapper flowNodeMapper;

    @Override
    public void process() {
        TaskOperationContext context = getContextBean(TaskOperationContext.class);
        Task task = taskService.getById(context.getTaskOperationBo().getTaskId());
        if (ObjectUtil.isNull(task)) {
            throw new ServiceException("任务不存在！");
        }
        FlowNode flowNode = flowNodeMapper.selectOne(QueryBuilder.lambda(FlowNode.class)
            .eq(FlowNode::getNodeCode, task.getNodeCode())
            .eq(FlowNode::getDefinitionId, task.getDefinitionId())
            .build());
        if (ObjectUtil.isNull(flowNode)) {
            throw new ServiceException("流程节点不存在");
        }
        if ((context.getOperation() == TaskOperationEnum.ADD_SIGNATURE || context.getOperation() == TaskOperationEnum.REDUCTION_SIGNATURE)
            && CooperateType.isOrSign(flowNode.getNodeRatio())) {
            throw new ServiceException(task.getNodeName() + "不是会签或票签节点！");
        }
        context.setTask(task);
        context.setFlowNode(flowNode);
    }

}
