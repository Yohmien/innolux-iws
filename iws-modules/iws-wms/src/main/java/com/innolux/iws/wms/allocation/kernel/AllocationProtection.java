/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 稀缺设备或深位通道的保护成本事实；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

/**
 * 稀缺设备/深位通道保护成本。调用方按已发布规则给出有序成本，内核不计算分数。
 *
 * <p>{@code costRank=0} 表示无额外保护；数值越小越优先。硬保护下限仍需通过
 * 容量或槽位事实的 {@code protectedRemaining} 表达。{@code reason} 用于候选解释，
 * 有保护成本时必须给出。</p>
 */
public record AllocationProtection(int costRank, String reason) {
    public static final AllocationProtection NONE = new AllocationProtection(0, "");

    public AllocationProtection {
        if (costRank < 0 || reason == null || (costRank > 0 && reason.isBlank())) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_STORAGE_INVALID);
        }
    }
}
