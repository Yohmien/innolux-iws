/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 不可拆单元的单维精确数量；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.math.BigDecimal;

/** 不可拆单元在一个容量维度上的精确数量、单位和精度。 */
public record AllocationQuantity(BigDecimal amount, String unit, int scale) {
    private static final int MAX_SCALE = 12;
    private static final int MAX_INTEGER_DIGITS = 26;

    public AllocationQuantity {
        if (!valid(amount, scale) || scale < 0 || scale > MAX_SCALE
            || unit == null || unit.isBlank()) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_QUANTITY_INVALID);
        }
    }

    static boolean valid(BigDecimal value, int scale) {
        return value != null
            && value.signum() > 0
            && value.scale() == scale
            && value.precision() - value.scale() <= MAX_INTEGER_DIGITS;
    }
}
