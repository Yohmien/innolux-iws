/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 单单元候选排序与硬拒绝只读解释；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个不可拆单元的只读候选解释。
 *
 * <p>候选按冻结合同排序，拒绝项按 storageId 排序。本结果不写计划实例、锁、
 * 占用或预留；{@code selectedStorageId} 只是预览首选，不是正式认领。</p>
 */
public record AllocationPreview(
    long allocationUnitId,
    Outcome outcome,
    List<Candidate> candidates,
    List<Rejection> rejections
) {
    public AllocationPreview {
        if (allocationUnitId <= 0 || outcome == null || candidates == null
            || rejections == null) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_INPUT_NULL);
        }
        candidates = List.copyOf(candidates);
        rejections = List.copyOf(rejections);
        if ((outcome == Outcome.CANDIDATES) != !candidates.isEmpty()) {
            throw new IllegalArgumentException("候选结果与 outcome 不一致");
        }
    }

    public Long selectedStorageId() {
        return candidates.isEmpty() ? null : candidates.getFirst().storageId();
    }

    public enum Outcome {
        CANDIDATES,
        NO_CANDIDATE
    }

    /**
     * 一个可分配候选。排序键依次为兼容等级、保护成本、容量残余（按策略维度顺序）
     * 或实体槽残余，最后是 storageId。容量模式 {@code slotResidual} 为空；实体槽模式
     * {@code capacityResidual} 为空。
     */
    public record Candidate(
        long storageId,
        int rank,
        Long channelId,
        int accessOrder,
        int compatibilityRank,
        int protectionCostRank,
        String protectionReason,
        Map<String, BigDecimal> capacityResidual,
        Integer slotResidual
    ) {
        public Candidate {
            if (storageId <= 0 || rank < 1 || (channelId != null && channelId <= 0)
                || accessOrder < 0 || compatibilityRank < 0 || compatibilityRank > 2
                || protectionCostRank < 0 || protectionReason == null
                || (protectionCostRank > 0 && protectionReason.isBlank())
                || capacityResidual == null
                || (capacityResidual.isEmpty() == (slotResidual == null))
                || (slotResidual != null && slotResidual < 0)) {
                throw new IllegalArgumentException("候选排序事实非法");
            }
            capacityResidual = Collections.unmodifiableMap(
                new LinkedHashMap<>(capacityResidual));
        }
    }

    /** 被硬筛选/保护规则拒绝的储位及稳定说明。 */
    public record Rejection(long storageId, Code code, String detail) {
        public Rejection {
            if (storageId <= 0 || code == null || detail == null) {
                throw new IllegalArgumentException("候选拒绝事实非法");
            }
        }

        public enum Code {
            STORAGE_DISABLED,
            SAFETY_BLOCKED,
            FORBIDDEN,
            PATH_UNREACHABLE,
            ACCESS_ORDER_MISSING,
            CHANNEL_BUSY,
            CAPACITY_MISSING,
            SLOT_MISSING,
            UNIT_MISMATCH,
            PRECISION_MISMATCH,
            INSUFFICIENT_RESIDUAL,
            PROTECTED_RESERVE,
            INSUFFICIENT_SLOTS,
            SLOT_PROTECTED
        }
    }
}
