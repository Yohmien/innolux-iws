/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 储位单维容量、占用、锁与保护余量快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.math.BigDecimal;

/**
 * 一个储位、一个容量维度的冻结实时账。
 *
 * <p>容量、占用、有效锁、业务扣留和保护余量分开保存；本类型不写锁，也不把数量
 * 换算成其他单位。保护余量是分配后必须保留的最小剩余量。</p>
 */
public record AllocationCapacityFact(
    BigDecimal capacity,
    BigDecimal occupied,
    BigDecimal locked,
    BigDecimal held,
    BigDecimal protectedRemaining,
    String unit,
    int scale
) {
    private static final int MAX_SCALE = 12;
    private static final int MAX_INTEGER_DIGITS = 26;

    public AllocationCapacityFact {
        if (!validNonNegative(capacity, scale) || !validNonNegative(occupied, scale)
            || !validNonNegative(locked, scale) || !validNonNegative(held, scale)
            || !validNonNegative(protectedRemaining, scale)
            || protectedRemaining.compareTo(capacity) > 0
            || scale < 0 || scale > MAX_SCALE
            || unit == null || unit.isBlank()) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_CAPACITY_INVALID);
        }
    }

    /** 当前可用于新分配的余量；负值原样保留，供调用方识别超占。 */
    public BigDecimal available() {
        return capacity.subtract(occupied).subtract(locked).subtract(held);
    }

    AllocationCapacityFact withLockedAdded(BigDecimal amount) {
        return new AllocationCapacityFact(
            capacity, occupied, locked.add(amount), held, protectedRemaining, unit, scale);
    }

    private static boolean validNonNegative(BigDecimal value, int scale) {
        return value != null
            && value.signum() >= 0
            && value.scale() >= 0
            && value.scale() <= scale
            && value.precision() - value.scale() <= MAX_INTEGER_DIGITS;
    }
}
