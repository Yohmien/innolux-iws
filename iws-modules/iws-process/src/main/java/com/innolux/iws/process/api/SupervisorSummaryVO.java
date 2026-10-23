/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督列表摘要
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.util.List;

/**
 * 监督实例摘要。
 *
 * @param supervisorInstanceId 监督实例 ID
 * @param supervisorKey 监督唯一键
 * @param scheduleId 业务调度 ID
 * @param businessScheduleCode 业务调度编码
 * @param planType 计划类型
 * @param scheduleScope 调度范围
 * @param mode 模式
 * @param planStatus 计划状态
 * @param controlState 控制状态
 * @param definitionVersionId 冻结定义版本
 * @param instanceVersion 实例版本
 * @param controlRevision 控制修订号
 * @param roundNo 最近轮次号
 * @param roundStatus 最近轮次状态
 * @param activePlanLimit 名额上限
 * @param heldSlots 当前占用名额
 * @param desiredState 期望平台状态
 * @param observedState 观测平台状态
 * @param wakePending 是否待平台唤醒
 * @param lastError 最近错误
 * @param allowedActions 可用动作
 * @param actionReason 动作限制说明
 */
public record SupervisorSummaryVO(String supervisorInstanceId, String supervisorKey, String scheduleId,
                                  String businessScheduleCode, String planType, String scheduleScope,
                                  String mode, String planStatus, String controlState,
                                  String definitionVersionId, String instanceVersion, Long controlRevision,
                                  Long roundNo, String roundStatus, Integer activePlanLimit, long heldSlots,
                                  String desiredState, String observedState, Boolean wakePending,
                                  String lastError, List<String> allowedActions, String actionReason) {
}
