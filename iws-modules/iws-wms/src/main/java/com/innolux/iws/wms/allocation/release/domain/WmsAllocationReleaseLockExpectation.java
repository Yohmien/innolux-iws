/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 固化单个分配锁在释放证据检查时的修订和效果状态；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.domain;

/**
 * 分配锁释放前期望值。
 *
 * @param allocationLockId 分配锁 ID
 * @param revision 检查时锁修订号
 * @param effectResult 检查时外部效果状态
 */
public record WmsAllocationReleaseLockExpectation(
    long allocationLockId,
    int revision,
    String effectResult
) {
}
