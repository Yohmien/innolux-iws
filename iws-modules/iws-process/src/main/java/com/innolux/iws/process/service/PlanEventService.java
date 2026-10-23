/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.1
 * @Description  : 事件重试、最终失败、重新校验与优先级命令；
 * @ModifyRecord : V1.0 新建；
 *                 V1.1 增加尝试分页查询；
 */

package com.innolux.iws.process.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.process.api.AttemptVO;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.EventCommand;
import com.innolux.iws.process.api.RetryEventCommand;

/**
 * 事件运行命令。
 */
public interface PlanEventService {

    /**
     * 请求事件重试。未知结果不得盲重放。
     *
     * @param command 重试命令
     * @return 命令结果
     */
    CommandResult retry(RetryEventCommand command);

    /**
     * 标记最终失败。
     *
     * @param command 命令
     * @return 命令结果
     */
    CommandResult finalizeEvent(EventCommand command);

    /**
     * 重新计算事件资格并记录观察。
     *
     * @param command 命令
     * @return 命令结果
     */
    CommandResult revalidate(EventCommand command);

    /**
     * 调整未认领事件优先级。
     *
     * @param command 命令
     * @return 命令结果
     */
    CommandResult setPriority(EventCommand command);

    /**
     * 分页查询事件尝试。
     *
     * @param eventId 事件 ID
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页数量
     * @return 尝试分页
     */
    PageResult<AttemptVO> attempts(long eventId, Integer pageNum, Integer pageSize);
}
