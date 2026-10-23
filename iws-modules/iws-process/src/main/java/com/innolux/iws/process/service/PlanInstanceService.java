/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.1
 * @Description  : 计划实例挂载、触发、确认、控制与查询契约；
 * @ModifyRecord : V1.0 新建；
 *                 V1.1 摘要增加动作准入元数据；
 */

package com.innolux.iws.process.service;

import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.ConfirmPostingCommand;
import com.innolux.iws.process.api.InstanceSummaryVO;
import com.innolux.iws.process.api.PlanControlCommand;
import com.innolux.iws.process.api.PriorityCommand;
import com.innolux.iws.process.api.ReceiveTriggerCommand;
import com.innolux.iws.process.api.TriggerReceiptResult;

/**
 * 计划实例应用服务。
 */
public interface PlanInstanceService {

    /**
     * 幂等挂载 QUEUE 业务任务。
     *
     * @param command 挂载命令
     * @return 挂载结果
     */
    AttachResult attach(AttachTaskCommand command);

    /**
     * 持久受理入站触发或结果回调。
     *
     * @param command 触发命令
     * @return 回执结果
     */
    TriggerReceiptResult receiveTrigger(ReceiveTriggerCommand command);

    /**
     * 逐系统首次确认。
     *
     * @param command 确认命令
     * @return 命令结果
     */
    CommandResult confirmPosting(ConfirmPostingCommand command);

    /**
     * 暂停计划。
     *
     * @param command 控制命令
     * @return 命令结果
     */
    CommandResult pause(PlanControlCommand command);

    /**
     * 恢复计划。
     *
     * @param command 控制命令
     * @return 命令结果
     */
    CommandResult resume(PlanControlCommand command);

    /**
     * 安全取消计划。
     *
     * @param command 控制命令
     * @return 命令结果
     */
    CommandResult cancel(PlanControlCommand command);

    /**
     * 准入前挂起。
     *
     * @param command 控制命令
     * @return 命令结果
     */
    CommandResult holdStart(PlanControlCommand command);

    /**
     * 解除准入前挂起。
     *
     * @param command 控制命令
     * @return 命令结果
     */
    CommandResult releaseStart(PlanControlCommand command);

    /**
     * 调整未认领实例优先级。
     *
     * @param command 优先级命令
     * @return 命令结果
     */
    CommandResult setPriority(PriorityCommand command);

    /**
     * 查询实例摘要、系统、token、事件与动作准入。
     *
     * @param instanceId 实例 ID
     * @return 实例摘要
     */
    InstanceSummaryVO get(long instanceId);
}
