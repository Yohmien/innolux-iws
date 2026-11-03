/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 结果 DSL
 * @Version      : V1.0
 * @Description  : 受信服务端显式提供的精确版本用途合同；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import java.util.HashSet;
import java.util.Set;

import static com.innolux.iws.wms.rule.dsl.RuleDslValues.error;

/**
 * 同一 purpose/version 的含义不可原位替换。空白名单不代表全部允许；不接受客户端声明用途合同。
 * source 合同、目标存在性/授权、allocation 地址禁排由正式适配器后续落实。
 */
public record RulePurposeContract(String purpose, String version, String scopeType,
                                  RuleResult.Type resultType, Set<String> allowedTargetTypes,
                                  Set<String> sortableAttributes) {
    public RulePurposeContract {
        requireText(purpose, "/purpose");
        requireText(version, "/version");
        requireText(scopeType, "/scopeType");
        if (resultType == null) { throw error("RESULT_CONTRACT_INVALID", "/resultType", "结果类型必填"); }
        allowedTargetTypes = immutable(allowedTargetTypes, "/allowedTargetTypes");
        sortableAttributes = immutable(sortableAttributes, "/sortableAttributes");
    }

    private static Set<String> immutable(Set<String> values, String path) {
        if (values == null) { throw error("RESULT_CONTRACT_INVALID", path, "白名单必须显式提供，允许空集合"); }
        Set<String> copy = new HashSet<>();
        for (String value : values) {
            requireText(value, path);
            copy.add(value);
        }
        return Set.copyOf(copy);
    }

    static void requireText(String value, String path) {
        if (value == null || value.isBlank()) { throw error("RESULT_CONTRACT_INVALID", path, "合同标识必须为非空白文本"); }
        // 不规范化合同标识；调用方随后必须使用完全相同的字符串查找。
        RuleDslValues.stringLimit(value, path, DslLimits.DEFAULT);
    }
}
