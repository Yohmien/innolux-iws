/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 纯预览的储位、维度与单元工作量上限；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

/** 纯预览的工作量上限，避免坏输入造成无界分配。 */
public record AllocationLimits(int maxStorages, int maxDimensions, int maxUnits) {
    public static final AllocationLimits DEFAULT = new AllocationLimits(2000, 32, 1000);

    public AllocationLimits {
        if (maxStorages < 1 || maxDimensions < 1 || maxUnits < 1) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_LIMIT_INVALID);
        }
    }
}
