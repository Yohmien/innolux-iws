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
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.utils.StreamUtils;
import com.innolux.iws.common.mybatis.core.query.QueryBuilder;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.orm.entity.FlowHisTask;
import org.dromara.warm.flow.orm.entity.FlowTask;
import org.dromara.warm.flow.orm.mapper.FlowHisTaskMapper;
import org.dromara.warm.flow.orm.mapper.FlowTaskMapper;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.common.enums.TaskStatusEnum;
import com.innolux.iws.workflow.domain.context.InstanceDeleteContext;

import java.util.List;
import java.util.Objects;

/**
 * 执行流程实例删除。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("instanceDeleteExecute")
public class InstanceDeleteExecuteComponent extends NodeComponent {

    private final InsService insService;
    private final FlowHisTaskMapper flowHisTaskMapper;
    private final FlowTaskMapper flowTaskMapper;

    @Override
    public void process() {
        InstanceDeleteContext context = getContextBean(InstanceDeleteContext.class);
        deleteCopyUsers(context.getDeleteInstanceIds());
        if (!context.isHistory()) {
            context.setResult(insService.remove(context.getDeleteInstanceIds()));
            return;
        }
        List<FlowTask> flowTaskList = flowTaskMapper.selectList(QueryBuilder.lambda(FlowTask.class)
            .in(FlowTask::getInstanceId, context.getDeleteInstanceIds())
            .build());
        if (CollUtil.isNotEmpty(flowTaskList)) {
            FlowEngine.userService().deleteByTaskIds(StreamUtils.toList(flowTaskList, FlowTask::getId));
        }
        FlowEngine.taskService().deleteByInsIds(context.getDeleteInstanceIds());
        FlowEngine.hisTaskService().deleteByInsIds(context.getDeleteInstanceIds());
        FlowEngine.insService().removeByIds(context.getDeleteInstanceIds());
        context.setResult(true);
    }

    /**
     * 删除流程实例产生的抄送人员关联。
     *
     * @param instanceIds 流程实例 ID 集合
     */
    private void deleteCopyUsers(List<Long> instanceIds) {
        List<FlowHisTask> copyTasks = flowHisTaskMapper.selectList(QueryBuilder.lambda(FlowHisTask.class)
            .in(FlowHisTask::getInstanceId, instanceIds)
            .eq(FlowHisTask::getFlowStatus, TaskStatusEnum.COPY.getStatus())
            .build());
        if (CollUtil.isEmpty(copyTasks)) {
            return;
        }
        List<Long> copyTaskIds = copyTasks.stream()
            .map(FlowHisTask::getTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (CollUtil.isNotEmpty(copyTaskIds)) {
            FlowEngine.userService().deleteByTaskIds(copyTaskIds);
        }
    }

}
