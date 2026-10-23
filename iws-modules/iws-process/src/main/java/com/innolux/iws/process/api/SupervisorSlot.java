/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督计划名额结果
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 监督准入结果。
 *
 * @param granted 是否持有 PLAN_SLOT
 * @param code 结果码
 * @param heldBefore 准入前占用数
 * @param heldAfter 准入后占用数
 * @param childInstanceVersion 子实例准入后版本
 */
public record SupervisorSlot(boolean granted, String code, int heldBefore, int heldAfter,
                             long childInstanceVersion) {
}
