/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 描述待追加的生产家谱边；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

import java.math.BigDecimal;

/**
 * 待追加生产家谱边。
 *
 * @param source 来源物料项引用
 * @param target 目标物料项引用
 * @param quantity 正数量
 * @param unit 与来源、目标一致的单位
 * @param groupNo 拆合关系组序号
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceEdgeInput(
    WmsMaterialTraceItemRef source,
    WmsMaterialTraceItemRef target,
    BigDecimal quantity,
    String unit,
    int groupNo
) {
}
