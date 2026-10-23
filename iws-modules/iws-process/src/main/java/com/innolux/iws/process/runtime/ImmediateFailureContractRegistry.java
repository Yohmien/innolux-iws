/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划安全失败合同注册表；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.enums.EventDirection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 按精确动作版本解析安全失败合同；重复或缺失都按未注册处理。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateFailureContractRegistry {

    private final Map<String, ImmediateFailureContract> contracts;

    /**
     * 汇总业务合同提供者。
     *
     * @param providers 合同提供者
     */
    public ImmediateFailureContractRegistry(ObjectProvider<ImmediateFailureContractProvider> providers) {
        Map<String, ImmediateFailureContract> collected = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (ImmediateFailureContractProvider provider : providers.orderedStream().toList()) {
            for (ImmediateFailureContract contract : provider.contracts()) {
                if (contract == null || contract.operationKey() == null || contract.operationKey().isBlank()) {
                    continue;
                }
                if (collected.putIfAbsent(contract.operationKey(), contract) != null) {
                    duplicates.add(contract.operationKey());
                }
            }
        }
        duplicates.forEach(collected::remove);
        this.contracts = Map.copyOf(collected);
    }

    /**
     * 事件定义对应的安全失败合同。
     *
     * @param definition 事件定义
     * @return 合同，缺失或重复返回 null
     */
    public ImmediateFailureContract find(ProcessEventDefinition definition) {
        String key = operationKey(definition);
        return key == null ? null : contracts.get(key);
    }

    /**
     * 精确能力键。
     *
     * @param definition 事件定义
     * @return 能力键，无法确定返回 null
     */
    public static String operationKey(ProcessEventDefinition definition) {
        if (definition.getEventDirection() == EventDirection.INTERNAL) {
            return definition.getLocalActionKey() == null || definition.getLocalActionVersion() == null ? null
                : "LOCAL:" + definition.getLocalActionKey() + ":" + definition.getLocalActionVersion();
        }
        return definition.getCallContractKey() == null || definition.getCallContractVersion() == null ? null
            : "CALL:" + definition.getCallContractKey() + ":" + definition.getCallContractVersion();
    }
}
