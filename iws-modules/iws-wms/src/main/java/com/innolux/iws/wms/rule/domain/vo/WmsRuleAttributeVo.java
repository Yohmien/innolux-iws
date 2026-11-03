/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 属性目录只读视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

import tools.jackson.databind.JsonNode;

/**
 * 属性目录只读视图。
 *
 * @param attributeKey    属性键
 * @param sourceType      来源类型
 * @param valueType       值类型
 * @param cardinality     基数
 * @param operators       允许操作符
 * @param unit            单位
 * @param scale           小数位
 * @param groupable       是否可分组
 * @param contractVersion 属性合同版本
 * @param revision        修订号
 * @author YONGJIE.PENG
 */
public record WmsRuleAttributeVo(String attributeKey, String sourceType, String valueType,
                                 String cardinality, JsonNode operators, String unit, Integer scale,
                                 Boolean groupable, String contractVersion, Integer revision) {
}
