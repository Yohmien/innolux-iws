/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划配置与查询 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 完整计划草稿原子保存参数。
 *
 * @param planCode 计划编码
 * @param planName 计划中文名
 * @param planVersion 业务版本
 * @param planType 计划类型
 * @param businessType 业务类型
 * @param businessScheduleCode 业务调度编码
 * @param selfSystemCode 本系统编码
 * @param planRole 计划角色
 * @param mode 监督模式
 * @param activePlanLimit 监督名额上限
 * @param fairnessWindowSize 监督公平窗口
 * @param maxExecutionMs 即时执行期限
 * @param completionPolicy 完成策略
 * @param exceptionStateCode 异常状态
 * @param roundBatchLimit 监督单轮上限
 * @param roundMaxDurationMs 监督单轮时长
 * @param workerBatchLimit worker 单轮上限
 * @param workerMaxDurationMs worker 单轮时长
 * @param leaseMs 租约时长
 * @param retryMax 自动重试上限
 * @param retryBackoffMs 退避间隔
 * @param description 说明
 * @param expectedVersion 聚合版本
 * @param catalogRevision 目录版本
 * @param states 状态集合
 * @param events 事件集合
 * @param edges 边集合
 * @param systemPolicies 系统模式集合
 */
public record DefinitionDraftBo(String planCode, String planName, Integer planVersion, String planType,
                                String businessType, String businessScheduleCode, String selfSystemCode,
                                String planRole, String mode, Integer activePlanLimit, Integer fairnessWindowSize,
                                Long maxExecutionMs, String completionPolicy, String exceptionStateCode,
                                Integer roundBatchLimit, Long roundMaxDurationMs, Integer workerBatchLimit,
                                Long workerMaxDurationMs, Long leaseMs, Integer retryMax, Long retryBackoffMs,
                                Long scheduleIntervalMs, String scheduleCron, Long jobTimeoutMs,
                                Integer jobMaxRetryTimes,
                                String description, Long expectedVersion, String catalogRevision,
                                java.util.List<DefinitionStateBo> states, java.util.List<DefinitionEventBo> events,
                                java.util.List<DefinitionEdgeBo> edges, java.util.List<SystemPolicyBo> systemPolicies) {
}
