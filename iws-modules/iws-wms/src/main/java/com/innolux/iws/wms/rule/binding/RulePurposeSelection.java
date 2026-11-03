/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布绑定
 * @Version      : V1.0
 * @Description  : 按 purpose 与 scopeType 显式选择精确用途合同版本；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.binding;

import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端用途版本选择；缺失时拒绝激活，不回落最新版本。 */
public final class RulePurposeSelection {

    private record Key(String purpose, String scopeType) {
    }

    private final Map<Key, String> versions;

    public RulePurposeSelection(Map<String, Map<String, String>> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("用途版本选择不能为空");
        }
        Map<Key, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> purposeEntry : selections.entrySet()) {
            String purpose = requireText(purposeEntry.getKey(), "purpose");
            if (purposeEntry.getValue() == null || purposeEntry.getValue().isEmpty()) {
                throw new IllegalArgumentException("用途必须显式配置作用域版本");
            }
            for (Map.Entry<String, String> scopeEntry : purposeEntry.getValue().entrySet()) {
                Key key = new Key(
                    purpose,
                    requireText(scopeEntry.getKey(), "scopeType")
                );
                if (copy.putIfAbsent(key, requireText(scopeEntry.getValue(), "version")) != null) {
                    throw new IllegalArgumentException("用途作用域版本重复");
                }
            }
        }
        versions = Map.copyOf(copy);
    }

    /**
     * 精确解析服务端选择的用途版本。
     *
     * @param purpose   用途编码
     * @param scopeType 作用域类型
     * @return 显式选择的版本
     */
    public String requireExactVersion(String purpose, String scopeType) {
        String version = versions.get(new Key(
            requireText(purpose, "purpose"),
            requireText(scopeType, "scopeType")
        ));
        if (version == null) {
            throw new ServiceException(
                "RULE_RESULT_VERSION_UNSELECTED: 用途和作用域未显式选择版本",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return version;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }
}
