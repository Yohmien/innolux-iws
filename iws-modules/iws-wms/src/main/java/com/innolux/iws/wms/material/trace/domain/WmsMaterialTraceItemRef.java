/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 引用本次追加项或补偿明确关联的原 trace 物料项；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

/**
 * 生产家谱边端点的类型化引用。
 *
 * @param itemRef           本次批次局部引用
 * @param originalTraceItemId 被补偿原 trace 的真实物料项 ID
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceItemRef(String itemRef, Long originalTraceItemId) {

    /**
     * 引用本次批次内的物料项。
     *
     * @param itemRef 本次批次局部引用
     * @return 类型化引用
     */
    public static WmsMaterialTraceItemRef current(String itemRef) {
        return new WmsMaterialTraceItemRef(itemRef, null);
    }

    /**
     * 引用明确关联原 trace 中已存在的物料项。
     *
     * @param itemId 原 trace 物料项 ID
     * @return 类型化引用
     */
    public static WmsMaterialTraceItemRef original(long itemId) {
        return new WmsMaterialTraceItemRef(null, itemId);
    }
}
