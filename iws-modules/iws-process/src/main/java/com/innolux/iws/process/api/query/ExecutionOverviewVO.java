/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 业务调度执行总览只读快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

import com.innolux.iws.common.core.domain.PageResult;
import java.util.List;

/**
 * 业务调度执行总览只读快照。
 *
 * @param asOf 快照时间
 * @param businessScheduleCode 业务调度编码
 * @param businessName 业务调度名称
 * @param configVersion 业务调度配置版本
 * @param orderingNote 排序说明
 * @param executedPlans 已结束计划分页
 * @param pendingPlans 未准入计划分页
 * @param runningPlans 运行中计划分页
 * @param exceptionPlans 异常、人工暂停和待确认计划分页
 * @param totals 各分类总数
 */
public record ExecutionOverviewVO(String asOf, String businessScheduleCode, String businessName,
                                  String configVersion, String orderingNote,
                                  PageResult<ExecutionPlanVO> executedPlans,
                                  PageResult<ExecutionPlanVO> pendingPlans,
                                  PageResult<ExecutionPlanVO> runningPlans,
                                  PageResult<ExecutionPlanVO> exceptionPlans,
                                  OverviewTotals totals) {

    /**
     * 总览分类总数。
     *
     * @param executed 已结束
     * @param pending 未准入
     * @param running 运行中
     * @param exception 异常或待人工
     */
    public record OverviewTotals(long executed, long pending, long running, long exception) {
    }

    /**
     * 计划摘要；待执行、运行中和已结束共用稳定字段，空字段表示该分类不适用。
     *
     * @param instanceId 实例 ID
     * @param instanceVersion 实例版本
     * @param planName 计划名称
     * @param planCode 计划编码
     * @param planVersion 计划版本
     * @param taskKey 任务键
     * @param businessId 业务 ID
     * @param planType 计划类型
     * @param planTypeName 计划类型名称
     * @param priority 优先级
     * @param planStatus 计划状态
     * @param statusText 中文状态
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     * @param updatedAt 更新时间
     * @param result 结果
     * @param completionSummary 完成摘要
     * @param lastEventName 最近事件名称
     * @param lastEventCode 最近事件编码
     * @param lastEventStatus 最近事件状态
     * @param exceptionState 异常状态
     * @param blockedReason 阻塞原因
     * @param queueRank 未准入候选位次
     * @param resourceScope 竞争范围
     * @param eligible 当前是否具备准入资格
     * @param blockReason 准入阻塞原因
     * @param readyTime 就绪时间
     * @param holdStart 是否准入前挂起
     * @param queueStatus 队列分类
     * @param branches 分支摘要
     * @param supervisorInstanceId 监督实例 ID
     * @param scheduleScope 调度范围
     * @param completedAt 完成时间
     */
    public record ExecutionPlanVO(String instanceId, String instanceVersion, String planName, String planCode,
                                  String planVersion, String taskKey, String businessId, String planType,
                                  String planTypeName, PriorityVO priority, String planStatus, String statusText,
                                  String startedAt, String finishedAt, String updatedAt, String result,
                                  String completionSummary, String lastEventName, String lastEventCode,
                                  String lastEventStatus, String exceptionState, String blockedReason,
                                  Integer queueRank, String resourceScope, Boolean eligible, String blockReason,
                                  String readyTime, Boolean holdStart, String queueStatus,
                                  List<BranchSummaryVO> branches, String supervisorInstanceId,
                                  String scheduleScope, String completedAt,
                                  List<String> allowedActions, java.util.Map<String, String> reasons) {
    }

    /**
     * 计划类型、实例和事件三层优先级。
     *
     * @param planType 类型优先级
     * @param instance 实例优先级
     * @param event 事件优先级
     */
    public record PriorityVO(int planType, int instance, int event) {
    }

    /**
     * 运行中计划的分支摘要。
     *
     * @param tokenId token ID
     * @param branchCode 分支编码
     * @param branchName 分支名称
     * @param currentState 当前状态
     * @param currentEvent 当前事件边
     * @param eventName 事件名称
     * @param eventCode 事件编码
     * @param eventDirection 事件方向
     * @param executionStatus 执行状态
     * @param attemptNo 尝试号
     * @param leaseEpoch 租约代次
     * @param startedAt 开始时间
     */
    public record BranchSummaryVO(String tokenId, String branchCode, String branchName,
                                  PlanChainVO.StateNodeVO currentState, PlanChainVO.EventChainEdgeVO currentEvent,
                                  String eventName, String eventCode, String eventDirection,
                                  String executionStatus, Integer attemptNo, String leaseEpoch, String startedAt) {
    }
}
