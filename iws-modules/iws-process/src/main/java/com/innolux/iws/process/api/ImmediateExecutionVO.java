/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划事件公开命令与结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 即时计划同步结果。
 *
 * @param commandKey 命令键
 * @param resultCode 领域结果码
 * @param message 中文说明
 * @param instanceId 计划实例 ID
 * @param supervisorInstanceId 监督实例 ID
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 * @param effectResult 效果结果
 * @param reconciliationRequired 是否需对账
 * @param queryUrl 命令查询地址
 * @param originalResponseCode 首次返回的领域结果码
 * @param asOf 当前事实生成时间
 */
public record ImmediateExecutionVO(String commandKey, String resultCode, String message, String instanceId,
                                   String supervisorInstanceId, java.time.LocalDateTime startedAt,
                                   java.time.LocalDateTime finishedAt, String effectResult,
                                   boolean reconciliationRequired, String queryUrl,
                                   String originalResponseCode, java.time.LocalDateTime asOf) {
}
