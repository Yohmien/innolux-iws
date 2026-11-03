/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 批量影子账逐单元候选结果；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.util.List;

/**
 * 批量预览结果，保持内核实际处理顺序。
 *
 * <p>同一形状按 best-fit-decreasing 处理；失败单元不阻断其他单元，结果只用于预览，
 * 不代表正式锁全部成功。</p>
 */
public record AllocationBatchPreview(List<AllocationPreview> units) {
    public AllocationBatchPreview {
        units = List.copyOf(units);
    }

    /** 至少一个单元无候选；成功单元仍保留解释，不能据此宣称整批已锁定。 */
    public boolean partial() {
        return units.stream().anyMatch(
            unit -> unit.outcome() == AllocationPreview.Outcome.NO_CANDIDATE);
    }
}
