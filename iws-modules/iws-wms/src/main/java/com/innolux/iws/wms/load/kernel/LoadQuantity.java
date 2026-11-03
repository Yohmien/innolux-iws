/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 承载物料叶实存数量及无默认值的汇总分组维度；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.math.BigDecimal;

/**
 * 物料叶当前实存。数量允许 0，不能为负，不得超过 decimal(20,6)。
 * 三个分组字段原样保留，不补默认值。未知单位或版本可表达原始事实，但汇总入口拒绝。
 * 仅 materialCode 的 null 值可作为汇总独立桶。
 */
public record LoadQuantity(BigDecimal amount, String materialCode, String unit,
                           String rulePackageVersion) {
    public LoadQuantity {
        if (!valid(amount)) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_QUANTITY_INVALID);
        }
    }

    static boolean valid(BigDecimal amount) {
        return amount != null
            && amount.signum() >= 0
            && amount.scale() <= LoadQuantityLimits.MAX_SCALE
            && amount.precision() - amount.scale() <= LoadQuantityLimits.MAX_INTEGER_DIGITS;
    }

    private static final class LoadQuantityLimits {
        private static final int MAX_SCALE = 6;
        private static final int MAX_INTEGER_DIGITS = 14;

        private LoadQuantityLimits() {
        }
    }
}
