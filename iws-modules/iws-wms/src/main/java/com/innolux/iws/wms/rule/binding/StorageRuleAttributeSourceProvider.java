/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则来源
 * @Version      : V1.0
 * @Description  : 首期生产 STORAGE 属性目录与事实来源；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.binding;

import com.innolux.iws.wms.rule.dsl.RuleAttributeBinding;
import com.innolux.iws.wms.rule.dsl.RuleDslEvaluator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 空间储位生产属性来源。
 *
 * <p>本期规则只开放 STORAGE 作用域的稳定展示和配置字段。事实读取使用储位及
 * 当前父链的持久行，不把物料生产属性混入空间规则。</p>
 */
@Component
public class StorageRuleAttributeSourceProvider implements RuleAttributeSourceProvider {

    public static final String SOURCE_TYPE = "STORAGE";

    public static final String CONTRACT_VERSION = "STORAGE-1";

    private static final Map<String, RuleAttributeBinding> BINDINGS = createBindings();

    private static final Set<String> SORTABLE = Set.of(
        "storageCode", "warehouseCode", "zoneCode", "storageType"
    );

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper;

    public StorageRuleAttributeSourceProvider(
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String sourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public String contractVersion() {
        return CONTRACT_VERSION;
    }

    @Override
    public Map<String, RuleAttributeBinding> bindings() {
        return BINDINGS;
    }

    @Override
    public Set<String> sortableAttributes() {
        return SORTABLE;
    }

    @Override
    public Map<String, RuleDslEvaluator.Fact> readFacts(
        long storageId,
        Set<String> attributeKeys
    ) {
        Map<String, Object> row = jdbcTemplate.query("""
            select s.storage_code, s.storage_type, s.status, s.disable_status,
                   z.zone_code, z.allow_inbound, z.allow_outbound,
                   w.warehouse_code
              from wms_storage s
              join wms_warehouse_zone z on z.zone_id = s.zone_id
              join wms_warehouse w on w.warehouse_id = s.warehouse_id
             where s.storage_id = ? and s.del_flag = '0'
            """, (rs, rowNum) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("storageCode", rs.getString("storage_code"));
            value.put("storageType", rs.getInt("storage_type"));
            value.put("status", rs.getInt("status"));
            value.put("disableStatus", rs.getInt("disable_status"));
            value.put("zoneCode", rs.getString("zone_code"));
            value.put("allowInbound", rs.getInt("allow_inbound") == 1);
            value.put("allowOutbound", rs.getInt("allow_outbound") == 1);
            value.put("warehouseCode", rs.getString("warehouse_code"));
            return value;
        }, storageId).stream().findFirst().orElseThrow(() ->
            new IllegalArgumentException("规则事实储位不存在: " + storageId));

        Map<String, RuleDslEvaluator.Fact> facts = new LinkedHashMap<>();
        for (String key : new LinkedHashSet<>(attributeKeys)) {
            Object value = row.get(key);
            if (value != null) {
                facts.put(key, new RuleDslEvaluator.Fact(
                    jsonMapper.valueToTree(value), null));
            }
        }
        return facts;
    }

    private static Map<String, RuleAttributeBinding> createBindings() {
        Map<String, RuleAttributeBinding> values = new LinkedHashMap<>();
        add(values, "storageCode", "STRING", "SCALAR",
            List.of("EQ", "NE", "IN", "IS_NULL"), null, null, true);
        add(values, "warehouseCode", "STRING", "SCALAR",
            List.of("EQ", "NE", "IN", "IS_NULL"), null, null, true);
        add(values, "zoneCode", "STRING", "SCALAR",
            List.of("EQ", "NE", "IN", "IS_NULL"), null, null, true);
        add(values, "storageType", "INTEGER", "SCALAR",
            List.of("EQ", "NE", "IN", "LT", "LE", "GT", "GE", "IS_NULL"),
            null, null, true);
        add(values, "allowInbound", "BOOLEAN", "SCALAR",
            List.of("EQ", "NE", "IN", "IS_NULL"), null, null, true);
        add(values, "allowOutbound", "BOOLEAN", "SCALAR",
            List.of("EQ", "NE", "IN", "IS_NULL"), null, null, true);
        return Map.copyOf(values);
    }

    private static void add(
        Map<String, RuleAttributeBinding> values,
        String key,
        String type,
        String cardinality,
        List<String> operators,
        String unit,
        Integer scale,
        boolean groupable
    ) {
        values.put(key, new RuleAttributeBinding(
            key, SOURCE_TYPE, type, cardinality, operators, unit, scale,
            groupable, "ACTIVE", CONTRACT_VERSION, 1));
    }
}
