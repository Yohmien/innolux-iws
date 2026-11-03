/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 聚合不可变物料事实头、物料项和生产家谱边快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

import java.util.List;

/**
 * 完整物料事实快照。
 *
 * @param history 事实头
 * @param items 物料项
 * @param edges 生产家谱边
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceSnapshot(
    WmsMaterialTraceHistorySnapshot history,
    List<WmsMaterialTraceItemSnapshot> items,
    List<WmsMaterialTraceEdgeSnapshot> edges
) {

    /**
     * 复制集合，避免快照集合被调用者修改。
     */
    public WmsMaterialTraceSnapshot {
        items = List.copyOf(items);
        edges = List.copyOf(edges);
    }
}
