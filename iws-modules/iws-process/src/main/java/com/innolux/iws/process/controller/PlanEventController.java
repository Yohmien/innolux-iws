/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.1
 * @Description  : 事件重试、终止、重新校验与尝试查询入口；
 * @ModifyRecord : V1.0 新建；
 *                 V1.1 尝试查询改为服务分页；
 */

package com.innolux.iws.process.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.process.api.AttemptVO;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.EventCommand;
import com.innolux.iws.process.api.RetryEventCommand;
import com.innolux.iws.process.service.PlanEventService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划事件运行命令入口。
 */
@Validated
@RestController
@RequestMapping("/process/event")
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanEventController {

    private final PlanEventService planEventService;

    /**
     * 构造控制器。
     *
     * @param planEventService 事件服务
     */
    public PlanEventController(PlanEventService planEventService) {
        this.planEventService = planEventService;
    }

    /**
     * 请求事件重试。
     *
     * @param eventId 事件 ID
     * @param command 命令
     * @return 命令结果
     */
    @SaCheckPermission("process:event:retry")
    @Log(title = "计划事件重试", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{eventId}/retry")
    public R<CommandResult> retry(@PathVariable long eventId, @RequestBody RetryEventCommand command) {
        if (command.eventId() != eventId) {
            return R.fail("路径事件与请求体不一致");
        }
        return R.ok(planEventService.retry(command));
    }

    /**
     * 标记最终失败。
     *
     * @param eventId 事件 ID
     * @param command 命令
     * @return 命令结果
     */
    @SaCheckPermission("process:event:finalize")
    @Log(title = "计划事件终止", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{eventId}/finalize")
    public R<CommandResult> finalizeEvent(@PathVariable long eventId, @RequestBody EventCommand command) {
        if (command.eventId() != eventId) {
            return R.fail("路径事件与请求体不一致");
        }
        return R.ok(planEventService.finalizeEvent(command));
    }

    /**
     * 重新校验。
     *
     * @param eventId 事件 ID
     * @param command 命令
     * @return 命令结果
     */
    @SaCheckPermission("process:event:revalidate")
    @PostMapping("/{eventId}/revalidate")
    public R<CommandResult> revalidate(@PathVariable long eventId, @RequestBody EventCommand command) {
        if (command.eventId() != eventId) {
            return R.fail("路径事件与请求体不一致");
        }
        return R.ok(planEventService.revalidate(command));
    }

    /**
     * 调整事件优先级。
     *
     * @param eventId 事件 ID
     * @param command 命令
     * @return 命令结果
     */
    @SaCheckPermission("process:event:priority")
    @Log(title = "计划事件优先级", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{eventId}/priority")
    public R<CommandResult> priority(@PathVariable long eventId, @RequestBody EventCommand command) {
        if (command.eventId() != eventId) {
            return R.fail("路径事件与请求体不一致");
        }
        return R.ok(planEventService.setPriority(command));
    }

    /**
     * 查询尝试。
     *
     * @param eventId 事件 ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 尝试分页
     */
    @SaCheckPermission("process:event:query")
    @GetMapping("/{eventId}/attempts")
    public R<PageResult<AttemptVO>> attempts(@PathVariable long eventId,
                                             @RequestParam(required = false) Integer pageNum,
                                             @RequestParam(required = false) Integer pageSize) {
        return R.ok(planEventService.attempts(eventId, pageNum, pageSize));
    }
}
