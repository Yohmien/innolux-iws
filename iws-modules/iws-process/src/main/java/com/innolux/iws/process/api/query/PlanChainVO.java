/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划实例两级执行描述只读快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

import java.util.List;
import java.util.Map;

/**
 * 计划实例两级执行描述只读快照。
 *
 * @param planInstanceId 实例 ID
 * @param instanceId 实例 ID 兼容字段
 * @param planCode 计划编码
 * @param planName 计划名称
 * @param planVersion 计划版本
 * @param taskKey 任务键
 * @param planStatus 计划状态
 * @param instanceVersion 实例版本
 * @param priority 优先级
 * @param systemContexts 系统模式快照
 * @param asOf 快照时间
 * @param graph 冻结图节点与事件边
 * @param branches 分支链
 * @param exception 异常描述
 * @param queueRank 未准入候选位次
 * @param resourceScope 竞争范围
 * @param eligible 当前是否具备准入资格
 * @param blockReason 准入阻塞原因
 * @param queueStatus 队列分类
 * @param holdStart 是否准入前挂起
 * @param businessId 业务 ID
 * @param planType 计划类型
 * @param supervisorInstanceId 监督实例 ID
 * @param scheduleScope 调度范围
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param finishedAt 结束时间
 * @param allowedActions 共享准入描述返回的可用动作
 * @param reasons 动作不可用原因
 */
public record PlanChainVO(String planInstanceId, String instanceId, String planCode, String planName,
                          String planVersion, String taskKey, String planStatus, String instanceVersion,
                          ExecutionOverviewVO.PriorityVO priority, List<SystemContextVO> systemContexts,
                          String asOf, GraphVO graph, List<BranchChainVO> branches,
                          ExceptionDescriptionVO exception, Integer queueRank, String resourceScope,
                          Boolean eligible, String blockReason, String queueStatus, Boolean holdStart,
                          String businessId, String planType, String supervisorInstanceId, String scheduleScope,
                          String createdAt, String updatedAt, String finishedAt,
                          List<String> allowedActions, Map<String, String> reasons) {

    /**
     * 冻结图节点与事件边。
     *
     * @param nodes 状态节点
     * @param edges 事件边
     */
    public record GraphVO(List<StateNodeVO> nodes, List<EventChainEdgeVO> edges) {
    }

    /**
     * 系统模式快照。
     *
     * @param systemCode 系统编码
     * @param systemName 系统名称
     * @param mode 过账模式
     * @param confirmed 是否首次确认
     * @param confirmedAt 确认时间
     * @param confirmedBy 确认人
     * @param confirmable 当前是否可确认
     * @param reasons 不可确认原因
     * @param eventCount 事件数
     */
    public record SystemContextVO(String systemCode, String systemName, String mode, Boolean confirmed,
                                  String confirmedAt, String confirmedBy, Boolean confirmable,
                                  List<String> reasons, Integer eventCount) {
    }

    /**
     * 状态节点描述。
     *
     * @param stateCode 状态编码
     * @param stateName 状态名称
     * @param description 中文说明
     * @param stateType 状态类别
     * @param terminal 是否终点
     * @param reached 是否已到达
     * @param reachedAt 到达时间
     * @param current 是否当前状态
     * @param validation 校验条件
     * @param decided 下一状态是否已决定
     * @param candidate 是否为候选下一状态
     * @param branchKind 分支类型
     */
    public record StateNodeVO(String stateCode, String stateName, String description, String stateType,
                              Boolean terminal, Boolean reached, String reachedAt, Boolean current,
                              List<ValidationVO> validation, Boolean decided, Boolean candidate,
                              String branchKind) {
    }

    /**
     * 事件边描述。
     *
     * @param eventId 事件 ID
     * @param eventVersion 事件版本
     * @param eventCode 事件编码
     * @param eventName 事件名称
     * @param eventDirection 事件方向
     * @param eventRole 事件角色
     * @param eventTypeKey 事件类型键
     * @param eventTypeVersion 事件类型版本
     * @param callContractKey 调用合同键
     * @param callContractVersion 调用合同版本
     * @param localActionKey 本地动作键
     * @param localActionVersion 本地动作版本
     * @param systemCode 系统编码
     * @param mode 过账模式
     * @param sourceStateCode 源状态
     * @param targetStateCode 目标状态
     * @param executionStatus 执行状态
     * @param latestAttemptNo 最近尝试号
     * @param blockedReason 阻塞原因
     * @param waitReason 等待原因
     * @param validation 校验条件
     * @param lastObservedAt 最近观察时间
     * @param queueRank 事件执行位次
     * @param eligible 当前是否可执行
     */
    public record EventChainEdgeVO(String eventId, String eventVersion, String eventCode, String eventName,
                                   String eventDirection, String eventRole, String eventTypeKey,
                                   String eventTypeVersion, String callContractKey, String callContractVersion,
                                   String localActionKey, String localActionVersion, String systemCode,
                                   String mode, String sourceStateCode, String targetStateCode,
                                   String executionStatus, Integer latestAttemptNo, String blockedReason,
                                   String waitReason, List<ValidationVO> validation, String lastObservedAt,
                                   Integer queueRank, Boolean eligible) {
    }

    /**
     * 分支链描述。
     *
     * @param tokenId token ID
     * @param branchCode 分支编码
     * @param branchName 分支名称
     * @param tokenStatus token 状态
     * @param tokenVersion token 版本
     * @param stateCode 已提交状态
     * @param currentState 当前状态描述
     * @param nextStates 下一状态描述
     * @param events 分支事件
     * @param currentEvent 当前事件
     * @param lastObservedAt 最近观察时间
     * @param scopeKey 范围键
     */
    public record BranchChainVO(String tokenId, String branchCode, String branchName, String tokenStatus,
                                String tokenVersion, String stateCode, StateNodeVO currentState,
                                List<StateNodeVO> nextStates, List<EventChainEdgeVO> events,
                                EventChainEdgeVO currentEvent, String lastObservedAt, String scopeKey) {
    }

    /**
     * 异常描述。
     *
     * @param stateCode 异常状态
     * @param stateName 异常状态名称
     * @param failedEventId 失败事件 ID
     * @param failedEventCode 失败事件编码
     * @param failedEventName 失败事件名称
     * @param callContractKey 调用合同键
     * @param failureCategory 失败分类
     * @param effectResult 效果结果
     * @param resumable 是否可续跑
     * @param blockedReason 阻塞原因
     * @param sourceStateCode 原源状态
     * @param targetStateCode 原目标状态
     * @param checkpoint 检查点
     * @param reconciliationRequired 是否需要对账
     */
    public record ExceptionDescriptionVO(String stateCode, String stateName, String failedEventId,
                                         String failedEventCode, String failedEventName,
                                         String callContractKey, String failureCategory, String effectResult,
                                         Boolean resumable, String blockedReason, String sourceStateCode,
                                         String targetStateCode, String checkpoint,
                                         Boolean reconciliationRequired) {
    }

    /**
     * 状态或事件校验条件。
     *
     * @param conditionCode 条件编码
     * @param description 中文说明
     * @param conditionType 条件类型
     * @param requiredFacts 所需事实
     * @param evidenceReference 证据引用
     * @param result 最近结果
     * @param observedAt 观察时间
     * @param ruleVersion 规则版本
     * @param mode 执行模式
     */
    public record ValidationVO(String conditionCode, String description, String conditionType,
                               List<String> requiredFacts, String evidenceReference, String result,
                               String observedAt, String ruleVersion, String mode) {
    }
}
