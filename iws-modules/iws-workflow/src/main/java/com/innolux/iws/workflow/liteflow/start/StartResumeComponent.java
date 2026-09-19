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
 * 已存在流程实例时执行续提交。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("startResume")
public class StartResumeComponent extends NodeComponent {

    private final TaskService taskService;
    private final InsService insService;
    private final FlwInstanceBizExtMapper flwInstanceBizExtMapper;

    @Override
    public void process() {
        StartProcessContext context = getContextBean(StartProcessContext.class);
        BusinessStatusEnum.checkStartStatus(context.getExistingInstance().getFlowStatus());
        List<Task> taskList = taskService.list(new FlowTask().setInstanceId(context.getExistingInstance().getId()));
        if (CollUtil.isEmpty(taskList)) {
            throw new ServiceException("流程实例缺少任务，请检查流程定义配置");
        }
        taskService.mergeVariable(context.getExistingInstance(), context.getVariables());
        insService.updateById(context.getExistingInstance());
        saveBizExt(context);
        context.setStartProcessReturn(new StartProcessReturnDTO(taskList.getFirst().getInstanceId(), taskList.getFirst().getId()));
    }

    private void saveBizExt(StartProcessContext context) {
        FlowInstanceBizExt bizExt = context.getBizExt();
        bizExt.setInstanceId(context.getExistingInstance().getId());
        bizExt.setBusinessId(context.getExistingInstance().getBusinessId());
        flwInstanceBizExtMapper.saveOrUpdateByInstanceId(bizExt);
    }

}
