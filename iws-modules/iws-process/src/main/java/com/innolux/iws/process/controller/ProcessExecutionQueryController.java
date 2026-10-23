/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划执行、实例链、事件、请求和审计只读入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.process.api.query.EventExecutionVO;
import com.innolux.iws.process.api.query.EventRequestVO;
import com.innolux.iws.process.api.query.ExecutionOverviewVO;
import com.innolux.iws.process.api.query.PlanChainVO;
import com.innolux.iws.process.api.query.ProcessAuditEntryVO;
import com.innolux.iws.process.api.query.ProcessAuditQuery;
import com.innolux.iws.process.api.query.ProcessExecutionOverviewQuery;
import com.innolux.iws.process.api.query.ProcessRequestQuery;
import com.innolux.iws.process.api.query.ProcessRequestRecordVO;
import com.innolux.iws.process.service.ProcessExecutionQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划执行只读查询入口。
 *
 * <p>GET 只读取已存事实，不触发重校验、重试、外部调用或任何状态写操作。</p>
 */
@Validated
@RestController
@RequestMapping("/process")
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessExecutionQueryController {

    private final ProcessExecutionQueryService queryService;

    /**
     * 构造只读查询控制器。
     *
     * @param queryService 查询服务
     */
    public ProcessExecutionQueryController(ProcessExecutionQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 查询业务调度执行总览。
     *
     * @param businessScheduleCode 业务调度编码
     * @param businessId 业务 ID
     * @param taskKey 任务键
     * @param planType 计划类型
     * @param instanceId 实例 ID
     * @param executedPageNum 已结束页码
     * @param executedPageSize 已结束每页数量
     * @param pendingPageNum 未准入页码
     * @param pendingPageSize 未准入每页数量
     * @param runningPageNum 运行中页码
     * @param runningPageSize 运行中每页数量
     * @param exceptionPageNum 异常页码
     * @param exceptionPageSize 异常每页数量
     * @return 总览
     */
    @SaCheckPermission("process:execution:list")
    @GetMapping("/execution/overview")
    public R<ExecutionOverviewVO> overview(
        @RequestParam(required = false) String businessScheduleCode,
        @RequestParam(required = false) String businessId,
        @RequestParam(required = false) String taskKey,
        @RequestParam(required = false) String planType,
        @RequestParam(required = false) String instanceId,
        @RequestParam(required = false) Integer executedPageNum,
        @RequestParam(required = false) Integer executedPageSize,
        @RequestParam(required = false) Integer pendingPageNum,
        @RequestParam(required = false) Integer pendingPageSize,
        @RequestParam(required = false) Integer runningPageNum,
        @RequestParam(required = false) Integer runningPageSize,
        @RequestParam(required = false) Integer exceptionPageNum,
        @RequestParam(required = false) Integer exceptionPageSize) {
        return R.ok(queryService.overview(new ProcessExecutionOverviewQuery(businessScheduleCode, businessId,
            taskKey, planType, instanceId, executedPageNum, executedPageSize, pendingPageNum,
            pendingPageSize, runningPageNum, runningPageSize, exceptionPageNum, exceptionPageSize)));
    }

    /**
     * 查询出入站请求方向记录，包含未关联计划的记录。
     *
     * @param requestId 本端请求 ID
     * @param direction 请求方向
     * @param purpose 调用用途
     * @param contractKey 调用合同键
     * @param resultCode 接收方业务结果码
     * @param effectResult 效果判定
     * @param instanceId 计划实例 ID
     * @param eventId 事件 ID
     * @param correlationId 调用链标识
     * @param businessScheduleCode 业务调度编码
     * @param businessId 业务 ID
     * @param taskKey 任务唯一键
     * @param linked 是否已关联计划
     * @param startedFrom 开始时间下限
     * @param startedTo 开始时间上限
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 请求记录安全分页
     */
    @SaCheckPermission("process:request:list")
    @GetMapping("/requests/page")
    public R<PageResult<ProcessRequestRecordVO>> requestPage(
        @RequestParam(required = false) String requestId,
        @RequestParam(required = false) String direction,
        @RequestParam(required = false) String purpose,
        @RequestParam(required = false) String contractKey,
        @RequestParam(required = false) String resultCode,
        @RequestParam(required = false) String effectResult,
        @RequestParam(required = false) String instanceId,
        @RequestParam(required = false) String eventId,
        @RequestParam(required = false) String correlationId,
        @RequestParam(required = false) String businessScheduleCode,
        @RequestParam(required = false) String businessId,
        @RequestParam(required = false) String taskKey,
        @RequestParam(required = false) Boolean linked,
        @RequestParam(required = false) String startedFrom,
        @RequestParam(required = false) String startedTo,
        @RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize) {
        return R.ok(queryService.requestPage(new ProcessRequestQuery(requestId, direction, purpose,
            contractKey, resultCode, effectResult, instanceId, eventId, correlationId,
            businessScheduleCode, businessId, taskKey, linked, startedFrom, startedTo, pageNum, pageSize)));
    }

    /**
     * 查询单条请求方向记录的安全详情，不返回原报文摘要。
     *
     * @param requestId 本端请求 ID
     * @return 请求记录安全详情
     */
    @SaCheckPermission("process:request:query")
    @GetMapping("/requests/{requestId}")
    public R<ProcessRequestRecordVO> requestRecord(@PathVariable String requestId) {
        return R.ok(queryService.requestRecord(requestId));
    }

    /**
     * 查询实例执行链。
     *
     * @param instanceId 实例 ID
     * @return 链快照
     */
    @SaCheckPermission("process:instance:chain")
    @GetMapping("/instance/{instanceId}/chain")
    public R<PlanChainVO> chain(@PathVariable long instanceId) {
        return R.ok(queryService.chain(instanceId));
    }

    /**
     * 查询实例领域审计。
     *
     * @param instanceId 实例 ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 审计分页
     */
    @SaCheckPermission("process:audit:query")
    @GetMapping("/instance/{instanceId}/audit")
    public R<PageResult<ProcessAuditEntryVO>> instanceAudit(@PathVariable long instanceId,
                                                            @RequestParam(required = false) Integer pageNum,
                                                            @RequestParam(required = false) Integer pageSize) {
        return R.ok(queryService.audit(new ProcessAuditQuery(null, String.valueOf(instanceId), null, null,
            null, null, null, null, pageNum, pageSize)));
    }

    /**
     * 查询单事件执行事实。
     *
     * @param eventId 事件 ID
     * @return 事件快照
     */
    @SaCheckPermission("process:event:query")
    @GetMapping("/event/{eventId}")
    public R<EventExecutionVO> event(@PathVariable long eventId) {
        return R.ok(queryService.event(eventId));
    }

    /**
     * 按独立权限懒加载单请求脱敏摘要。
     *
     * @param eventId 事件 ID
     * @param requestId 请求 ID
     * @return 请求明细
     */
    @SaCheckPermission("process:event:request")
    @GetMapping("/event/{eventId}/requests/{requestId}")
    public R<EventRequestVO> request(@PathVariable long eventId, @PathVariable String requestId) {
        return R.ok(queryService.request(eventId, requestId));
    }

    /**
     * 查询事件领域审计。
     *
     * @param eventId 事件 ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 审计分页
     */
    @SaCheckPermission("process:audit:query")
    @GetMapping("/event/{eventId}/audit")
    public R<PageResult<ProcessAuditEntryVO>> eventAudit(@PathVariable long eventId,
                                                         @RequestParam(required = false) Integer pageNum,
                                                         @RequestParam(required = false) Integer pageSize) {
        return R.ok(queryService.audit(new ProcessAuditQuery(null, null, String.valueOf(eventId), null,
            null, null, null, null, pageNum, pageSize)));
    }

    /**
     * 查询领域审计分页。
     *
     * @param businessScheduleCode 业务调度编码
     * @param instanceId 实例 ID
     * @param eventId 事件 ID
     * @param operator 操作者
     * @param action 动作
     * @param result 结果
     * @param occurredFrom 开始时间
     * @param occurredTo 结束时间
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 审计分页
     */
    @SaCheckPermission("process:audit:query")
    @GetMapping("/audit/page")
    public R<PageResult<ProcessAuditEntryVO>> audit(
        @RequestParam(required = false) String businessScheduleCode,
        @RequestParam(required = false) String instanceId,
        @RequestParam(required = false) String eventId,
        @RequestParam(required = false) String operator,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String result,
        @RequestParam(required = false) String occurredFrom,
        @RequestParam(required = false) String occurredTo,
        @RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize) {
        return R.ok(queryService.audit(new ProcessAuditQuery(businessScheduleCode, instanceId, eventId,
            operator, action, result, occurredFrom, occurredTo, pageNum, pageSize)));
    }
}
