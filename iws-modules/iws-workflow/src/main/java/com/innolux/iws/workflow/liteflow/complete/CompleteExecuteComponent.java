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

import cn.hutool.core.convert.Convert;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.enums.BusinessStatusEnum;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.service.TaskService;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.common.enums.TaskStatusEnum;
import com.innolux.iws.workflow.domain.bo.CompleteTaskBo;
import com.innolux.iws.workflow.domain.context.CompleteTaskContext;

import java.util.Map;

import static com.innolux.iws.workflow.common.constant.FlowConstant.*;

/**
 * 构建办理参数并执行当前任务跳转。
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@RequiredArgsConstructor
@LiteflowComponent("completeExecute")
public class CompleteExecuteComponent extends NodeComponent {

    private final TaskService taskService;

    @Override
    public void process() {
        CompleteTaskContext context = getContextBean(CompleteTaskContext.class);
        CompleteTaskBo completeTaskBo = context.getCompleteTaskBo();
        Map<String, Object> variables = completeTaskBo.getVariables();
        FlowParams flowParams = FlowParams.build()
            .handler(completeTaskBo.getHandler())
            .variable(variables)
            .ignore(Convert.toBool(variables.getOrDefault(VAR_IGNORE, false)))
            .ignoreDepute(Convert.toBool(variables.getOrDefault(VAR_IGNORE_DEPUTE, false)))
            .ignoreCooperate(Convert.toBool(variables.getOrDefault(VAR_IGNORE_COOPERATE, false)))
            .skipType(SkipType.PASS.getKey())
            .message(completeTaskBo.getMessage())
            .flowStatus(BusinessStatusEnum.WAITING.getStatus())
            .hisStatus(TaskStatusEnum.PASS.getStatus())
            .hisTaskExt(completeTaskBo.getFileId());

        context.setFlowParams(flowParams);
        context.setAutoPass(Convert.toBool(context.getInstance().getVariableMap().getOrDefault(AUTO_PASS, false)));
        taskService.skip(context.getFlowTask().getId(), flowParams);
    }

}
