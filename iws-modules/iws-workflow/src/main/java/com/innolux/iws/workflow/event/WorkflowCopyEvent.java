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

package com.innolux.iws.workflow.event;

import cn.hutool.core.collection.CollUtil;
import org.dromara.warm.flow.core.entity.Task;
import com.innolux.iws.workflow.domain.bo.FlowCopyBo;

import java.util.List;

/**
 * 工作流抄送事件。
 *
 * @param task         当前任务
 * @param flowCopyList 抄送人列表
 */
public record WorkflowCopyEvent(Task task, List<FlowCopyBo> flowCopyList) {

    public WorkflowCopyEvent {
        if (CollUtil.isNotEmpty(flowCopyList)) {
            flowCopyList = List.copyOf(flowCopyList);
        }
    }

}
