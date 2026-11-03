package com.innolux.iws.wms.rule.binding;

import com.innolux.iws.wms.rule.dsl.RuleAttributeBinding;
import com.innolux.iws.wms.rule.dsl.RuleDslEvaluator;

import java.util.Map;
import java.util.Set;

public interface RuleAttributeSourceProvider {
    String sourceType();
    String contractVersion();
    Map<String, RuleAttributeBinding> bindings();
    Set<String> sortableAttributes();
    Map<String, RuleDslEvaluator.Fact> readFacts(long storageId, Set<String> attributeKeys);
}
