/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督控制命令结果
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.util.List;

/**
 * 监督控制结果。
 *
 * @param supervisorInstanceId 监督实例 ID
 * @param code 结果码
 * @param message 中文说明
 * @param controlState 控制状态
 * @param desiredState 期望平台状态
 * @param observedState 观测平台状态
 * @param revision 最新控制修订号
 * @param wakePending 是否待平台唤醒
 * @param allowedActions 可用动作
 */
public record SupervisorControlResult(String supervisorInstanceId, String code, String message,
                                      String controlState, String desiredState, String observedState,
                                      long revision, boolean wakePending, List<String> allowedActions) {
}
