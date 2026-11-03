package com.innolux.iws.wms.rule;

import com.innolux.iws.wms.rule.binding.RuleAttributeSourceRegistry;
import com.innolux.iws.wms.rule.binding.RulePurposeSelection;
import com.innolux.iws.wms.rule.binding.StorageRuleAttributeSourceProvider;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingConfiguration;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.dsl.RulePurposeContract;
import com.innolux.iws.wms.rule.dsl.RulePurposeRegistry;
import com.innolux.iws.wms.rule.dsl.RuleResult;
import com.innolux.iws.wms.rule.mapper.WmsRuleAttributeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 正式 STORAGE 来源、目录种子和绑定编译组合验收。 */
class WmsRuleProductionBindingIntegrationTest extends WmsRuleTestSupport {

    @Autowired
    private WmsRuleAttributeMapper attributeMapper;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void productionBeanCompilesStorageRuleFromFormalCatalogRows() {
        jdbcTemplate.update("delete from wms_attribute_catalog");
        jdbcTemplate.update("""
            insert into wms_attribute_catalog (
                attribute_key, source_type, value_type, cardinality, operators_json,
                unit, scale, groupable, status, contract_version, revision
            ) values
                ('storageCode', 'STORAGE', 'STRING', 'SCALAR',
                 json_array('EQ', 'NE', 'IN', 'IS_NULL'), null, null, 1, 'ACTIVE', 'STORAGE-1', 1),
                ('warehouseCode', 'STORAGE', 'STRING', 'SCALAR',
                 json_array('EQ', 'NE', 'IN', 'IS_NULL'), null, null, 1, 'ACTIVE', 'STORAGE-1', 1),
                ('zoneCode', 'STORAGE', 'STRING', 'SCALAR',
                 json_array('EQ', 'NE', 'IN', 'IS_NULL'), null, null, 1, 'ACTIVE', 'STORAGE-1', 1),
                ('storageType', 'STORAGE', 'INTEGER', 'SCALAR',
                 json_array('EQ', 'NE', 'IN', 'LT', 'LE', 'GT', 'GE', 'IS_NULL'),
                 null, null, 1, 'ACTIVE', 'STORAGE-1', 1),
                ('allowInbound', 'STORAGE', 'BOOLEAN', 'SCALAR',
                 json_array('EQ', 'NE', 'IN', 'IS_NULL'), null, null, 1, 'ACTIVE', 'STORAGE-1', 1),
                ('allowOutbound', 'STORAGE', 'BOOLEAN', 'SCALAR',
                 json_array('EQ', 'NE', 'IN', 'IS_NULL'), null, null, 1, 'ACTIVE', 'STORAGE-1', 1)
            """);

        StorageRuleAttributeSourceProvider provider =
            new StorageRuleAttributeSourceProvider(jdbcTemplate, jsonMapper);
        RuleAttributeSourceRegistry sources =
            new RuleAttributeSourceRegistry(List.of(provider));
        RulePurposeSelection selection = new RulePurposeSelection(Map.of(
            WmsRuleBindingConfiguration.PURPOSE_FILTER,
            Map.of("STORAGE", WmsRuleBindingConfiguration.PURPOSE_VERSION),
            WmsRuleBindingConfiguration.PURPOSE_SORT,
            Map.of("STORAGE", WmsRuleBindingConfiguration.PURPOSE_VERSION)
        ));
        RulePurposeRegistry purposes = new RulePurposeRegistry(List.of(
            new RulePurposeContract(
                WmsRuleBindingConfiguration.PURPOSE_FILTER,
                WmsRuleBindingConfiguration.PURPOSE_VERSION,
                "STORAGE",
                RuleResult.Type.FILTER,
                Set.of(),
                Set.of()
            ),
            new RulePurposeContract(
                WmsRuleBindingConfiguration.PURPOSE_SORT,
                WmsRuleBindingConfiguration.PURPOSE_VERSION,
                "STORAGE",
                RuleResult.Type.SORT,
                Set.of(),
                sources.sortableAttributes()
            )
        ));
        WmsRuleBindingService service =
            new WmsRuleBindingService(attributeMapper, sources, purposes, selection);

        WmsRuleDefinition definition = new WmsRuleDefinition();
        definition.setPurpose(WmsRuleBindingConfiguration.PURPOSE_FILTER);
        definition.setScopeType("STORAGE");
        WmsRuleVersion version = new WmsRuleVersion();
        version.setConditionJson("""
            {"schema":"iws.rule.condition/1","root":{
              "type":"SCALAR_COMPARE","attribute":"storageCode",
              "operator":"EQ","value":"S-10"}}
            """);
        version.setResultJson("""
            {"schema":"iws.rule.result/1","type":"FILTER","value":true}
            """);

        WmsRuleBindingService.Compiled compiled =
            new TransactionTemplate(transactionManager).execute(status ->
                service.compile(definition, version));

        assertThat(compiled).isNotNull();
        assertThat(compiled.snapshotJson())
            .contains("\"sourceType\":\"STORAGE\"")
            .contains("\"contractVersion\":\"STORAGE-1\"")
            .contains("\"attributeKey\":\"storageCode\"");
        assertThat(compiled.digest()).matches("[0-9a-f]{64}");
    }
}
