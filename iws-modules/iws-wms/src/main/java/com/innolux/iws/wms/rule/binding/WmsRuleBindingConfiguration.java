package com.innolux.iws.wms.rule.binding;

import com.innolux.iws.wms.rule.dsl.RulePurposeContract;
import com.innolux.iws.wms.rule.dsl.RulePurposeRegistry;
import com.innolux.iws.wms.rule.dsl.RuleResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class WmsRuleBindingConfiguration {
    public static final String PURPOSE_FILTER = "ALLOCATION_CANDIDATE_FILTER";
    public static final String PURPOSE_SORT = "ALLOCATION_CANDIDATE_SORT";
    public static final String PURPOSE_VERSION = "1";

    @Bean
    RuleAttributeSourceRegistry ruleAttributeSourceRegistry(ObjectProvider<RuleAttributeSourceProvider> providers) {
        return new RuleAttributeSourceRegistry(providers.orderedStream().toList());
    }

    @Bean
    RulePurposeSelection rulePurposeSelection() {
        return new RulePurposeSelection(Map.of(
            PURPOSE_FILTER, Map.of("STORAGE", PURPOSE_VERSION),
            PURPOSE_SORT, Map.of("STORAGE", PURPOSE_VERSION)
        ));
    }

    @Bean
    RulePurposeRegistry rulePurposeRegistry(RuleAttributeSourceRegistry sources) {
        return new RulePurposeRegistry(List.of(
            new RulePurposeContract(PURPOSE_FILTER, PURPOSE_VERSION, "STORAGE", RuleResult.Type.FILTER,
                Set.of(), Set.of()),
            new RulePurposeContract(PURPOSE_SORT, PURPOSE_VERSION, "STORAGE", RuleResult.Type.SORT,
                Set.of(), sources.sortableAttributes())
        ));
    }
}
