/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 承载物料 trace 生产家谱边的插入和读取列；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.mapper;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 物料 trace 生产家谱边持久化行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsMaterialTraceEdgeRow {

    private Long traceEdgeId;

    private Long traceId;

    private Long sourceItemId;

    private Long targetItemId;

    private BigDecimal quantity;

    private String unit;

    private Integer groupNo;

    private LocalDateTime recordedAt;

    private Long createDept;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;
}
