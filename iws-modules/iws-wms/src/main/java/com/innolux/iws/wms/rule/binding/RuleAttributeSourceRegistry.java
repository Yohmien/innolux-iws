package com.innolux.iws.wms.rule.binding;

import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.rule.dsl.RuleAttributeBinding;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class RuleAttributeSourceRegistry {
    private record Key(String sourceType, String version) { }
    private final Map<Key, RuleAttributeSourceProvider> providers;

    public RuleAttributeSourceRegistry(Collection<RuleAttributeSourceProvider> entries) {
        Map<Key, RuleAttributeSourceProvider> registered = new LinkedHashMap<>();
        for (RuleAttributeSourceProvider provider : entries) {
            if (provider == null || provider.sourceType() == null || provider.sourceType().isBlank()
                || provider.contractVersion() == null || provider.contractVersion().isBlank()) {
                throw new IllegalArgumentException("来源 provider 精确键必填");
            }
            Map<String, RuleAttributeBinding> bindings = Map.copyOf(provider.bindings());
            Set<String> sortable = Set.copyOf(provider.sortableAttributes());
            if (bindings.isEmpty() || !bindings.keySet().containsAll(sortable)) {
                throw new IllegalArgumentException("来源字段必填，排序白名单必须属于来源字段");
            }
            for (Map.Entry<String, RuleAttributeBinding> entry : bindings.entrySet()) {
                RuleAttributeBinding binding = entry.getValue();
                if (!entry.getKey().equals(binding.attributeKey())
                    || !provider.sourceType().equals(binding.sourceType())
                    || !provider.contractVersion().equals(binding.contractVersion())) {
                    throw new IllegalArgumentException("来源字段与 provider 精确合同不一致");
                }
            }
            RuleAttributeSourceProvider frozen = new RuleAttributeSourceProvider() {
                public String sourceType() { return provider.sourceType(); }
                public String contractVersion() { return provider.contractVersion(); }
                public Map<String, RuleAttributeBinding> bindings() { return bindings; }
                public Set<String> sortableAttributes() { return sortable; }
                public Map<String, com.innolux.iws.wms.rule.dsl.RuleDslEvaluator.Fact> readFacts(
                    long storageId, Set<String> attributeKeys) {
                    if (!bindings.keySet().containsAll(attributeKeys)) {
                        throw new IllegalArgumentException("事实请求含未登记属性");
                    }
                    return Map.copyOf(provider.readFacts(storageId, Set.copyOf(attributeKeys)));
                }
            };
            if (registered.putIfAbsent(new Key(provider.sourceType(), provider.contractVersion()), frozen) != null) {
                throw new IllegalArgumentException("来源精确合同重复");
            }
        }
        providers = Map.copyOf(registered);
    }

    public RuleAttributeSourceProvider resolveExact(String sourceType, String contractVersion) {
        RuleAttributeSourceProvider provider = providers.get(new Key(sourceType, contractVersion));
        if (provider == null) {
            throw new ServiceException("RULE_SOURCE_CONTRACT_NOT_FOUND: 来源精确合同未开放", HttpStatus.HTTP_CONFLICT);
        }
        return provider;
    }

    public Set<String> sortableAttributes() {
        Set<String> attributes = new TreeSet<>();
        providers.values().forEach(provider -> attributes.addAll(provider.sortableAttributes()));
        return Set.copyOf(attributes);
    }
}
