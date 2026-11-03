/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL
 * @Version      : V1.0
 * @Description  : 由可信适配器提供的不可变属性目录快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import java.util.List;

/**
 * 服务端目录快照，不能由客户端声明覆盖。允许操作符使用列表以保留并检查目录重复项。
 * precision 固定为 20；DECIMAL 的 scale 为 0..6。来源注册与旧版本快照装载由适配器负责。
 */
public record RuleAttributeBinding(String attributeKey, String sourceType, String valueType,
                                   String cardinality, List<String> operators, String unit,
                                   Integer scale, boolean groupable, String status,
                                   String contractVersion, int revision) {
    public RuleAttributeBinding {
        operators = operators == null ? null : java.util.Collections.unmodifiableList(
            new java.util.ArrayList<>(operators));
    }
}
