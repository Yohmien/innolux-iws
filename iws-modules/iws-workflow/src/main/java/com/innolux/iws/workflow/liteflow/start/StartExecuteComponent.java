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

package com.innolux.iws.workflow.liteflow.start;

import cn.hutool.core.collection.CollUtil;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.enums.BusinessStatusEnum;
import com.innolux.iws.common.core.exception.ServiceException;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.orm.entity.FlowTask;
import com.innolux.iws.workflow.api.domain.StartProcessReturnDTO;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.FlowInstanceBizExt;
import com.innolux.iws.workflow.domain.context.StartProcessContext;
import com.innolux.iws.workflow.mapper.FlwInstanceBizExtMapper;

import java.util.List;

/**
 * 启动流程实例并构建返回结果。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("startExecute")
public class StartExecuteComponent extends NodeComponent {

    private final InsService insService;
    private final TaskService taskService;
    private final FlwInstanceBizExtMapper flwInstanceBizExtMapper;

    @Override
    public void process() {
        StartProcessContext context = getContextBean(StartProcessContext.class);
        FlowParams flowParams = FlowParams.build()
            .handler(context.getStartProcessBo().getHandler())
            .flowCode(context.getStartProcessBo().getFlowCode())
            .variable(context.getVariables())
            .flowStatus(BusinessStatusEnum.DRAFT.getStatus());
        Instance instance = insService.start(context.getBusinessId(), flowParams);
        context.setInstance(instance);
        saveBizExt(context, instance);

        List<Task> taskList = taskService.list(new FlowTask().setInstanceId(instance.getId()));
        if (CollUtil.isEmpty(taskList)) {
            throw new ServiceException("流程启动失败，未生成任务");
        }
        if (taskList.size() > 1) {
            throw new ServiceException("请检查流程第一个环节是否为申请人！");
        }
        context.setTaskList(taskList);
        context.setStartProcessReturn(new StartProcessReturnDTO(instance.getId(), taskList.getFirst().getId()));
    }

    private void saveBizExt(StartProcessContext context, Instance instance) {
        FlowInstanceBizExt bizExt = context.getBizExt();
        bizExt.setInstanceId(instance.getId());
        bizExt.setBusinessId(instance.getBusinessId());
        flwInstanceBizExtMapper.saveOrUpdateByInstanceId(bizExt);
    }

}
