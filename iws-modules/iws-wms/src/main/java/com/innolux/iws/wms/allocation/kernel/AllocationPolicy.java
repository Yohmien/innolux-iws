/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 冻结多容量维度的逐维比较顺序；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.util.HashSet;
import java.util.List;

/** 冻结排序合同；容量模式按给定维度顺序逐维比较，不合成加权总分。 */
public record AllocationPolicy(List<String> dimensionOrder) {
    public AllocationPolicy {
        if (dimensionOrder == null) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_DIMENSION_INVALID);
        }
        dimensionOrder = List.copyOf(dimensionOrder);
        HashSet<String> seen = new HashSet<>();
        for (String dimension : dimensionOrder) {
            if (dimension == null || dimension.isBlank() || !seen.add(dimension)) {
                throw AllocationKernelException.failure(
                    AllocationKernelException.Code.ALLOCATION_DIMENSION_INVALID);
            }
        }
    }
}
