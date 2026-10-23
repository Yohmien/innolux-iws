/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划同步执行与命令只读查询入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.runtime.ImmediateExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 即时计划入口。
 */
@Validated
@RestController
@RequestMapping("/process/schedule")
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ImmediateExecutionController {

    private final ImmediateExecutionService immediateExecutionService;

    /**
     * 构造控制器。
     *
     * @param immediateExecutionService 即时执行服务
     */
    public ImmediateExecutionController(ImmediateExecutionService immediateExecutionService) {
        this.immediateExecutionService = immediateExecutionService;
    }

    /**
     * 同步执行即时计划。
     *
     * @param scheduleId 业务调度 ID
     * @param command 命令
     * @return 当前命令事实
     */
    @SaCheckPermission("process:instance:execute")
    @Log(title = "即时计划执行", businessType = BusinessType.OTHER)
    @PostMapping("/{scheduleId}/execute-immediate")
    public R<ImmediateExecutionVO> execute(@PathVariable long scheduleId,
                                           @RequestBody ExecuteImmediateCommand command) {
        if (command == null || command.scheduleId() != scheduleId) {
            return R.fail("路径调度与请求体 scheduleId 不一致");
        }
        return R.ok(immediateExecutionService.execute(command));
    }

    /**
     * 只读查询原命令最新事实。
     *
     * @param scheduleId 业务调度 ID
     * @param commandKey 命令键
     * @return 当前命令事实
     */
    @SaCheckPermission("process:schedule:query")
    @GetMapping("/{scheduleId}/commands/{commandKey}")
    public R<ImmediateExecutionVO> command(@PathVariable long scheduleId, @PathVariable String commandKey) {
        return R.ok(immediateExecutionService.find(scheduleId, commandKey));
    }
}
