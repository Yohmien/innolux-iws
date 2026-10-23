/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督查询、轮次/调度记录与人工控制入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.process.api.SupervisorActivateVersionRequest;
import com.innolux.iws.process.api.SupervisorCommandRequest;
import com.innolux.iws.process.api.SupervisorControlResult;
import com.innolux.iws.process.api.SupervisorDetailVO;
import com.innolux.iws.process.api.SupervisorDispatchRecordVO;
import com.innolux.iws.process.api.SupervisorRoundVO;
import com.innolux.iws.process.api.SupervisorSummaryVO;
import com.innolux.iws.process.runtime.SupervisorControlService;
import com.innolux.iws.process.runtime.SupervisorQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 监督查询与控制入口。
 */
@Validated
@RestController
@RequestMapping("/process")
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorController {

    private final SupervisorQueryService queryService;

    private final SupervisorControlService controlService;

    /**
     * 构造控制器。
     *
     * @param queryService 查询服务
     * @param controlService 控制服务
     */
    public SupervisorController(SupervisorQueryService queryService,
                                SupervisorControlService controlService) {
        this.queryService = queryService;
        this.controlService = controlService;
    }

    /**
     * 分页查询监督。
     *
     * @param scheduleId 业务调度 ID
     * @param planType 计划类型
     * @param scope 范围
     * @param mode 模式
     * @param status 状态
     * @param pageQuery 分页
     * @return 分页
     */
    @SaCheckPermission("process:schedule:query")
    @GetMapping("/schedule/{scheduleId}/supervisors")
    public R<PageResult<SupervisorSummaryVO>> supervisors(@PathVariable long scheduleId,
                                                          @RequestParam(required = false) String planType,
                                                          @RequestParam(required = false) String scope,
                                                          @RequestParam(required = false) String mode,
                                                          @RequestParam(required = false) String status,
                                                          PageQuery pageQuery) {
        return R.ok(queryService.pageSupervisors(scheduleId, planType, scope, mode, status, pageQuery));
    }

    /**
     * 监督详情。
     *
     * @param id 监督实例 ID
     * @return 详情
     */
    @SaCheckPermission("process:schedule:query")
    @GetMapping("/supervisor/{id}")
    public R<SupervisorDetailVO> detail(@PathVariable long id) {
        return R.ok(queryService.detail(id));
    }

    /**
     * 监督轮次。
     *
     * @param id 监督实例 ID
     * @param roundNo 轮次
     * @param status 状态
     * @param pageQuery 分页
     * @return 分页
     */
    @SaCheckPermission("process:schedule:query")
    @GetMapping("/supervisor/{id}/rounds")
    public R<PageResult<SupervisorRoundVO>> rounds(@PathVariable long id,
                                                   @RequestParam(required = false) Long roundNo,
                                                   @RequestParam(required = false) String status,
                                                   PageQuery pageQuery) {
        return R.ok(queryService.pageRounds(id, roundNo, status, pageQuery));
    }

    /**
     * 监督调度记录。
     *
     * @param id 监督实例 ID
     * @param roundNo 轮次
     * @param childInstanceId 子计划
     * @param commandKey 命令键
     * @param action 动作
     * @param result 结果
     * @param from 起始
     * @param to 结束
     * @param pageQuery 分页
     * @return 分页
     */
    @SaCheckPermission("process:schedule:query")
    @GetMapping("/supervisor/{id}/dispatch-records")
    public R<PageResult<SupervisorDispatchRecordVO>> dispatchRecords(
        @PathVariable long id,
        @RequestParam(required = false) Long roundNo,
        @RequestParam(required = false) Long childInstanceId,
        @RequestParam(required = false) String commandKey,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String result,
        @RequestParam(required = false) LocalDateTime from,
        @RequestParam(required = false) LocalDateTime to,
        PageQuery pageQuery) {
        return R.ok(queryService.pageDispatchRecords(id, roundNo, childInstanceId, commandKey, action, result,
            from, to, pageQuery));
    }

    /**
     * 暂停监督。
     *
     * @param id 监督实例 ID
     * @param request 命令
     * @return 控制结果
     */
    @SaCheckPermission("process:schedule:pause")
    @Log(title = "监督暂停", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/supervisor/{id}/pause")
    public R<SupervisorControlResult> pause(@PathVariable long id,
                                            @RequestBody SupervisorCommandRequest request) {
        return R.ok(controlService.pause(id, request.expectedVersion(), request.commandKey(), request.reason()));
    }

    /**
     * 恢复监督。
     *
     * @param id 监督实例 ID
     * @param request 命令
     * @return 控制结果
     */
    @SaCheckPermission("process:schedule:resume")
    @Log(title = "监督恢复", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/supervisor/{id}/resume")
    public R<SupervisorControlResult> resume(@PathVariable long id,
                                             @RequestBody SupervisorCommandRequest request) {
        return R.ok(controlService.resume(id, request.expectedVersion(), request.commandKey(), request.reason()));
    }

    /**
     * 重启 worker 控制意图。
     *
     * @param id 监督实例 ID
     * @param request 命令
     * @return 控制结果
     */
    @SaCheckPermission("process:schedule:restart")
    @Log(title = "监督重启 Worker", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/supervisor/{id}/restart-worker")
    public R<SupervisorControlResult> restartWorker(@PathVariable long id,
                                                    @RequestBody SupervisorCommandRequest request) {
        return R.ok(controlService.restartWorker(id, request.expectedVersion(), request.commandKey(),
            request.reason()));
    }

    /**
     * 切换监督冻结版本。
     *
     * @param id 监督实例 ID
     * @param request 命令
     * @return 控制结果
     */
    @SaCheckPermission("process:schedule:edit")
    @Log(title = "监督版本切换", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/supervisor/{id}/activate-version")
    public R<SupervisorControlResult> activateVersion(@PathVariable long id,
                                                      @RequestBody SupervisorActivateVersionRequest request) {
        return R.ok(controlService.activateVersion(id, request.expectedVersion(),
            request.definitionVersionId(), request.commandKey(), request.reason()));
    }
}
