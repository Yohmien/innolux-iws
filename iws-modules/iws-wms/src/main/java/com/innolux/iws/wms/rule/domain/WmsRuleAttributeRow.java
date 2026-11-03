/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 属性目录只读行，对应 wms_attribute_catalog；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain;

import lombok.Data;

/**
 * 属性目录只读行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class WmsRuleAttributeRow {

    private String attributeKey;

    private String sourceType;

    private String valueType;

    private String cardinality;

    private String operatorsJson;

    private String unit;

    private Integer scale;

    private Boolean groupable;

    private String contractVersion;

    private String status;

    private Integer revision;
}
