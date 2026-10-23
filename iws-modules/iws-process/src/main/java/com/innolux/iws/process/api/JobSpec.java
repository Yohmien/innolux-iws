/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 调度平台最小任务规格；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * job 创建或更新平台任务所需的最小完整规格。
 *
 * @param executorName 固定执行器
 * @param jobName 中文运维名
 * @param bizId 稳定技术任务 ID
 * @param scheduleType CRON/FIXED_RATE/ONE_SHOT
 * @param cronExpression CRON 表达式
 * @param intervalMs 固定间隔毫秒
 * @param timeoutMs 单次触发超时毫秒
 * @param maxRetryTimes 平台重试次数，领域默认 0
 * @param paramsJson 执行参数 JSON
 * @param routeKey 路由键，默认 DEFAULT
 */
public record JobSpec(String executorName, String jobName, String bizId, String scheduleType,
                      String cronExpression, Long intervalMs, Long timeoutMs, Integer maxRetryTimes,
                      String paramsJson, String routeKey) {
}
