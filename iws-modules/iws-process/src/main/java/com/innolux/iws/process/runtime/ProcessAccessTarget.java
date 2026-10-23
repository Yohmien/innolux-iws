/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 对象范围授权目标；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 命令与查询共享的对象授权目标。
 *
 * @param instanceId 实例 ID
 * @param eventId 事件 ID
 * @param scheduleId 业务调度 ID
 * @param businessType 业务类型
 * @param businessId 业务 ID
 * @param taskKey 任务键
 * @param scheduleScope 调度范围
 * @param action 动作
 */
public record ProcessAccessTarget(long instanceId, Long eventId, Long scheduleId, String businessType,
                                  String businessId, String taskKey, String scheduleScope, String action) {
}
