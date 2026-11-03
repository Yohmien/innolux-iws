/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存业务目录
 * @Version      : V1.0
 * @Description  : 业务计划挂载与现有调度解析只读行；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.catalog.domain;

import lombok.Data;

/**
 * 业务计划挂载与现有调度解析只读行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsBusinessPlanMountRow {

    private Long mountId;

    private String businessTypeCode;

    private String planTypeCode;

    private String planTypeName;

    private String scheduleCode;

    private String contractVersion;

    private Boolean enabled;

    private Integer revision;

    private Long scheduleId;

    private Boolean scheduleEnabled;

    private String scheduleDelFlag;

    private Long planTypeId;

    private Boolean planTypeEnabled;
}
