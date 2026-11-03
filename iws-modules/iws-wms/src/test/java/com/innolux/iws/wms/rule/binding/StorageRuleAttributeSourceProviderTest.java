package com.innolux.iws.wms.rule.binding;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StorageRuleAttributeSourceProviderTest {

    @Test
    void productionProviderRegistersExactStorageContract() {
        StorageRuleAttributeSourceProvider provider = new StorageRuleAttributeSourceProvider(
            mock(JdbcTemplate.class), JsonMapper.builder().build());
        RuleAttributeSourceRegistry registry =
            new RuleAttributeSourceRegistry(List.of(provider));

        assertThat(provider.bindings()).containsKeys(
            "storageCode", "warehouseCode", "zoneCode", "storageType",
            "allowInbound", "allowOutbound");
        assertThat(registry.resolveExact("STORAGE", "STORAGE-1")).isSameAs(
            registry.resolveExact("STORAGE", "STORAGE-1"));
    }
}
