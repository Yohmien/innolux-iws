/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 定义不可变物料事实 trace 的事实类型；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

/**
 * 物料事实 trace 类型。
 *
 * @author YONGJIE.PENG
 */
public enum WmsMaterialTraceKind {

    /**
     * 原始成功事实。
     */
    FACT,

    /**
     * 追加的反向补偿事实。
     */
    COMPENSATION
}
