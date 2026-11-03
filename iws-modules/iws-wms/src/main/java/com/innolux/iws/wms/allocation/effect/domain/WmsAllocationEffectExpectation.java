/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果代次
 * @Version      : V1.0
 * @Description  : 固化单把分配锁的期望代次和效果状态；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.domain;

/**
 * 分配锁效果推进期望。
 *
 * @param allocationLockId 分配锁 ID
 * @param expectedGeneration 期望锁 generation，取 revision
 * @param expectedEffectResult 期望当前外部效果
 */
public record WmsAllocationEffectExpectation(
    long allocationLockId,
    int expectedGeneration,
    String expectedEffectResult
) {
}
