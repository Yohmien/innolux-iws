/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 结果 DSL
 * @Version      : V1.0
 * @Description  : 无默认注册或最新版本回退的用途合同注册表；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static com.innolux.iws.wms.rule.dsl.RuleDslValues.error;

/** 由受信启动配置显式构造；不是生产 Bean，不注册任何真实库存用途。 */
public final class RulePurposeRegistry {
    private record Key(String purpose, String version) { }
    private final Map<Key, RulePurposeContract> contracts;

    public RulePurposeRegistry(Collection<RulePurposeContract> entries) {
        if (entries == null) { throw error("RESULT_CONTRACT_INVALID", "", "用途注册集合必填"); }
        Map<Key, RulePurposeContract> copy = new HashMap<>();
        for (RulePurposeContract entry : entries) {
            if (entry == null) { throw error("RESULT_CONTRACT_INVALID", "", "用途注册项不能为空"); }
            if (copy.putIfAbsent(new Key(entry.purpose(), entry.version()), entry) != null) {
                throw error("RESULT_CONTRACT_DUPLICATE", "/purpose", "用途及版本重复注册");
            }
        }
        contracts = Map.copyOf(copy);
    }

    /** 精确解析历史或显式选择的版本；缺失、版本不同、作用域不同均拒绝，不选最高版本。 */
    public RulePurposeContract resolveExact(String purpose, String version, String scopeType) {
        RulePurposeContract.requireText(purpose, "/purpose");
        RulePurposeContract.requireText(version, "/version");
        RulePurposeContract.requireText(scopeType, "/scopeType");
        RulePurposeContract contract = contracts.get(new Key(purpose, version));
        if (contract == null) { throw error("RESULT_CONTRACT_NOT_FOUND", "/purpose", "用途精确版本未注册"); }
        if (!contract.scopeType().equals(scopeType)) {
            throw error("RESULT_SCOPE_MISMATCH", "/scopeType", "用途合同作用域不匹配");
        }
        return contract;
    }

    /** 激活版本映射必须由受信服务端传入，缺映射不得推定最新版本；本方法不执行激活。 */
    public RulePurposeContract resolveForActivation(String purpose, String scopeType,
                                                    Map<String, String> explicitVersions) {
        RulePurposeContract.requireText(purpose, "/purpose");
        if (explicitVersions == null || !explicitVersions.containsKey(purpose)
            || explicitVersions.get(purpose) == null) {
            throw error("RESULT_VERSION_UNSELECTED", "/version", "未显式选择用途版本");
        }
        return resolveExact(purpose, explicitVersions.get(purpose), scopeType);
    }
}
