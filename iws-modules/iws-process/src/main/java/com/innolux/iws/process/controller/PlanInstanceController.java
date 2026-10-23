/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划实例挂载、触发、确认与控制入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.ConfirmPostingCommand;
import com.innolux.iws.process.api.InstanceSummaryVO;
import com.innolux.iws.process.api.PlanControlCommand;
import com.innolux.iws.process.api.PriorityCommand;
import com.innolux.iws.process.api.ReceiveTriggerCommand;
import com.innolux.iws.process.api.TriggerReceiptResult;
import com.innolux.iws.process.service.PlanInstanceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划实例入口。
 */
@Validated
@RestController
@RequestMapping("/process/instance")
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanInstanceController {

    private final PlanInstanceService planInstanceService;

    /**
     * 构造控制器。
     *
     * @param planInstanceService 实例服务
     */
    public PlanInstanceController(PlanInstanceService planInstanceService) {
        this.planInstanceService = planInstanceService;
    }

    /**
     * 挂载业务任务。
     *
     * @param command 挂载命令
     * @return 挂载结果
     */
    @SaCheckPermission("process:instance:attach")
    @Log(title = "计划挂载", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/attach")
    public R<AttachResult> attach(@RequestBody AttachTaskCommand command) {
        return R.ok(planInstanceService.attach(command));
    }

    /**
     * 受理入站触发或结果回调。
     *
     * @param instanceId 实例 ID
     * @param command 触发命令
     * @return 回执结果
     */
    @SaCheckPermission("process:trigger:receive")
    @PostMapping("/{instanceId}/trigger")
    public R<TriggerReceiptResult> trigger(@PathVariable long instanceId,
                                           @RequestBody ReceiveTriggerCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.receiveTrigger(command));
    }

    /**
     * 逐系统首次确认。
     *
     * @param instanceId 实例 ID
     * @param command 确认命令
     * @return 命令结果
     */
    @SaCheckPermission("process:instance:confirm")
    @Log(title = "计划首次确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{instanceId}/confirm-posting")
    public R<CommandResult> confirm(@PathVariable long instanceId,
                                    @RequestBody ConfirmPostingCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.confirmPosting(command));
    }

    /**
     * 暂停计划。
     *
     * @param instanceId 实例 ID
     * @param command 控制命令
     * @return 命令结果
     */
    @SaCheckPermission("process:instance:pause")
    @Log(title = "计划暂停", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{instanceId}/pause")
    public R<CommandResult> pause(@PathVariable long instanceId, @RequestBody PlanControlCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.pause(command));
    }

    /**
     * 恢复计划。
     *
     * @param instanceId 实例 ID
     * @param command 控制命令
     * @return 命令结果
     */
    @SaCheckPermission("process:instance:resume")
    @Log(title = "计划恢复", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{instanceId}/resume")
    public R<CommandResult> resume(@PathVariable long instanceId, @RequestBody PlanControlCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.resume(command));
    }

    /**
     * 安全取消计划。
     *
     * @param instanceId 实例 ID
     * @param command 控制命令
     * @return 命令结果
     */
    @SaCheckPermission("process:instance:cancel")
    @Log(title = "计划取消", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{instanceId}/cancel")
    public R<CommandResult> cancel(@PathVariable long instanceId, @RequestBody PlanControlCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.cancel(command));
    }

    /**
     * 准入前挂起。
     *
     * @param instanceId 实例 ID
     * @param command 控制命令
     * @return 命令结果
     */
    @SaCheckPermission("process:instance:hold")
    @Log(title = "计划挂起", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{instanceId}/hold-start")
    public R<CommandResult> holdStart(@PathVariable long instanceId, @RequestBody PlanControlCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.holdStart(command));
    }

    /**
     * 解除准入前挂起。
     *
     * @param instanceId 实例 ID
     * @param command 控制命令
     * @return 命令结果
     */
    @SaCheckPermission("process:instance:release")
    @Log(title = "计划放行", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{instanceId}/release-start")
    public R<CommandResult> releaseStart(@PathVariable long instanceId, @RequestBody PlanControlCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.releaseStart(command));
    }

    /**
     * 调整实例优先级。
     *
     * @param instanceId 实例 ID
     * @param command 优先级命令
     * @return 命令结果
     */
    @SaCheckPermission("process:instance:priority")
    @Log(title = "计划优先级", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{instanceId}/priority")
    public R<CommandResult> priority(@PathVariable long instanceId, @RequestBody PriorityCommand command) {
        if (command.instanceId() != instanceId) {
            return R.fail("路径实例与请求体不一致");
        }
        return R.ok(planInstanceService.setPriority(command));
    }

    /**
     * 查询实例摘要。
     *
     * @param instanceId 实例 ID
     * @return 实例摘要
     */
    @SaCheckPermission("process:instance:query")
    @GetMapping("/{instanceId}")
    public R<InstanceSummaryVO> get(@PathVariable long instanceId) {
        return R.ok(planInstanceService.get(instanceId));
    }
}
