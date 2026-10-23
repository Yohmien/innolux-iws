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
 * 完整计划版本详情。
 *
 * @param summary 版本摘要
 * @param selfSystemCode 本系统编码
 * @param businessType 业务类型
 * @param businessScheduleCode 业务调度编码
 * @param mode 监督模式
 * @param activePlanLimit 名额上限
 * @param fairnessWindowSize 公平窗口
 * @param maxExecutionMs 即时期限
 * @param completionPolicy 完成策略
 * @param exceptionStateCode 异常状态
 * @param states 状态
 * @param events 事件
 * @param edges 边
 * @param systemPolicies 系统模式
 * @param roundBatchLimit 监督单轮上限
 * @param roundMaxDurationMs 监督单轮时长
 * @param workerBatchLimit worker 单轮上限
 * @param workerMaxDurationMs worker 单轮时长
 * @param leaseMs 租约时长
 * @param retryMax 自动重试上限
 * @param retryBackoffMs 退避间隔
 * @param scheduleIntervalMs 监督固定间隔
 * @param scheduleCron 监督 CRON
 * @param jobTimeoutMs 平台触发超时
 * @param jobMaxRetryTimes 平台自动重试次数
 * @param description 计划说明
 */
public record DefinitionDetailVo(DefinitionSummaryVo summary, String selfSystemCode, String businessType,
                                 String businessScheduleCode, String mode, Integer activePlanLimit,
                                 Integer fairnessWindowSize, Long maxExecutionMs, String completionPolicy,
                                 String exceptionStateCode, java.util.List<DefinitionStateBo> states,
                                 java.util.List<DefinitionEventBo> events, java.util.List<DefinitionEdgeBo> edges,
                                 java.util.List<SystemPolicyBo> systemPolicies, Integer roundBatchLimit,
                                 Long roundMaxDurationMs, Integer workerBatchLimit, Long workerMaxDurationMs,
                                 Long leaseMs, Integer retryMax, Long retryBackoffMs, Long scheduleIntervalMs,
                                 String scheduleCron, Long jobTimeoutMs, Integer jobMaxRetryTimes,
                                 String description) {
}
