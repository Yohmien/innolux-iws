/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 表达已写入的生产家谱边；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 已写入生产家谱边快照。
 *
 * @author YONGJIE.PENG
 */
public record WmsMaterialTraceEdgeSnapshot(
    long traceEdgeId,
    long traceId,
    long sourceItemId,
    long targetItemId,
    BigDecimal quantity,
    String unit,
    int groupNo,
    LocalDateTime recordedAt,
    Long createDept,
    Long createBy,
    LocalDateTime createTime,
    Long updateBy,
    LocalDateTime updateTime
) {
}
