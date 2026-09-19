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

package com.innolux.iws.workflow.domain.context;

import lombok.Data;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.orm.entity.FlowNode;
import com.innolux.iws.workflow.common.enums.TaskOperationEnum;
import com.innolux.iws.workflow.domain.bo.TaskOperationBo;

/**
 * 任务操作 LiteFlow 上下文。
 * <p>
 * 用于在委派、转办、加签、减签链路中传递操作类型、任务、节点和办理参数。
 *
 * @author YONGJIE.PENG
 */
@Data
public class TaskOperationContext {

    /**
     * 任务操作入参。
     */
    private final TaskOperationBo taskOperationBo;

    /**
     * 操作类型编码。
     */
    private final String taskOperation;

    /**
     * 解析后的操作枚举。
     */
    private TaskOperationEnum operation;

    /**
     * Warm-Flow 操作参数。
     */
    private FlowParams flowParams;

    /**
     * 当前任务。
     */
    private Task task;

    /**
     * 当前任务对应的流程节点。
     */
    private FlowNode flowNode;

    /**
     * 操作执行结果。
     */
    private boolean result;

}
