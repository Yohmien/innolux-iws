/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 结果 DSL
 * @Version      : V1.0
 * @Description  : FILTER/TARGET_SET/SORT 封闭结构及受信用途合同校验；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.innolux.iws.wms.rule.dsl.RuleDslValues.error;

/**
 * 受信调用方显式提供精确版本合同与属性绑定，不允许 result 覆盖元数据。
 * 仅校验结构，不证明来源合同、目标存在性/授权、allocation 地址禁排或现场发布门禁成立。
 */
public final class RuleResultValidator {
    public static final String SCHEMA = "iws.rule.result/1";
    // 沿用条件入口相同的 Jackson 严格特性；不修改已验条件文件或引入另一种 JSON 解析器。
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]*");
    private final DslLimits limits;

    public RuleResultValidator() { this(DslLimits.DEFAULT); }

    public RuleResultValidator(DslLimits limits) { this.limits = java.util.Objects.requireNonNull(limits); }

    /** 原始 JSON 入口拒绝任意层重复 key、尾随 JSON 和解析器限制外输入。 */
    public RuleResult validate(String json, RulePurposeContract contract, Map<String, RuleAttributeBinding> bindings) {
        if (json == null) { throw error("RESULT_INVALID_DOCUMENT", "", "结果文档必填"); }
        JsonNode document;
        try {
            document = MAPPER.readTree(json);
        } catch (tools.jackson.core.JacksonException ex) {
            throw error("RESULT_INVALID_DOCUMENT", "", "结果 JSON 无效、含重复 key 或超出解析限制");
        }
        return validate(document, contract, bindings);
    }

    /** 树入口无法恢复上游已丢失的重复 key；调用适配器必须另行保证严格解析。 */
    public RuleResult validate(JsonNode document, RulePurposeContract contract,
                               Map<String, RuleAttributeBinding> bindings) {
        if (contract == null) { throw error("RESULT_CONTRACT_INVALID", "", "受信用途合同必填"); }
        if (document == null || !document.isObject()) { throw error("RESULT_INVALID_DOCUMENT", "", "结果必须为对象"); }
        if (!SCHEMA.equals(text(document.get("schema"), "/schema"))) {
            throw error("RESULT_INVALID_DOCUMENT", "/schema", "结果 schema 未登记");
        }
        String type = text(document.get("type"), "/type");
        if (!contract.resultType().name().equals(type)) {
            throw error("RESULT_TYPE_MISMATCH", "/type", "结果类型与用途合同不匹配");
        }
        return switch (contract.resultType()) {
            case FILTER -> filter(document, contract);
            case TARGET_SET -> targets(document, contract);
            case SORT -> sort(document, contract, bindings);
        };
    }

    private RuleResult filter(JsonNode document, RulePurposeContract contract) {
        fields(document, "", Set.of("schema", "type", "value"));
        if (!document.get("value").isBoolean()) {
            throw error("RESULT_VALUE_INVALID", "/value", "FILTER value 必须为 JSON boolean");
        }
        return new RuleResult(contract, new RuleResult.Filter(document.get("value").booleanValue()), Map.of());
    }

    private RuleResult targets(JsonNode document, RulePurposeContract contract) {
        fields(document, "", Set.of("schema", "type", "objectType", "ids"));
        String objectType = text(document.get("objectType"), "/objectType");
        if (!contract.allowedTargetTypes().contains(objectType)) {
            throw error("RESULT_TARGET_TYPE_NOT_ALLOWED", "/objectType", "用途不允许该目标对象类型");
        }
        JsonNode ids = document.get("ids");
        array(ids, "/ids");
        List<Long> parsed = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            long id = id(ids.get(i), "/ids/" + i);
            if (!seen.add(id)) { throw error("RESULT_VALUE_INVALID", "/ids/" + i, "目标 ID 重复"); }
            parsed.add(id);
        }
        parsed.sort(Long::compareTo);
        return new RuleResult(contract, new RuleResult.TargetSet(objectType, parsed), Map.of());
    }

    private RuleResult sort(JsonNode document, RulePurposeContract contract, Map<String, RuleAttributeBinding> bindings) {
        fields(document, "", Set.of("schema", "type", "keys"));
        JsonNode keys = document.get("keys");
        array(keys, "/keys");
        if (bindings == null) { throw error("BINDING_INVALID", "/keys", "受信属性绑定必填"); }
        List<RuleResult.SortKey> compiled = new ArrayList<>();
        Map<String, RuleAttributeBinding> selected = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String path = "/keys/" + i;
            JsonNode key = keys.get(i);
            fields(key, path, Set.of("attribute", "direction", "nulls"));
            String attribute = text(key.get("attribute"), path + "/attribute");
            if (!contract.sortableAttributes().contains(attribute)) {
                throw error("RESULT_SORT_NOT_ALLOWED", path + "/attribute", "属性不在该用途可排序白名单");
            }
            if (selected.containsKey(attribute)) {
                throw error("RESULT_VALUE_INVALID", path + "/attribute", "排序属性重复");
            }
            RuleAttributeBinding binding = bindings.get(attribute);
            RuleDslValues.binding(binding, path + "/attribute", limits);
            if (!attribute.equals(binding.attributeKey())) {
                throw error("BINDING_INVALID", path + "/attribute", "绑定键与属性键不一致");
            }
            if (!"SCALAR".equals(binding.cardinality())) {
                throw error("CARDINALITY_MISMATCH", path + "/attribute", "排序只接受可比较 SCALAR 属性");
            }
            String direction = text(key.get("direction"), path + "/direction");
            String nulls = text(key.get("nulls"), path + "/nulls");
            if (!Set.of("ASC", "DESC").contains(direction)) {
                throw error("RESULT_VALUE_INVALID", path + "/direction", "排序方向仅允许 ASC/DESC");
            }
            if (!Set.of("FIRST", "LAST").contains(nulls)) {
                throw error("RESULT_VALUE_INVALID", path + "/nulls", "空值顺序仅允许 FIRST/LAST");
            }
            compiled.add(new RuleResult.SortKey(attribute, RuleResult.Direction.valueOf(direction), RuleResult.Nulls.valueOf(nulls)));
            selected.put(attribute, binding);
        }
        return new RuleResult(contract, new RuleResult.Sort(compiled), selected);
    }

    private long id(JsonNode json, String path) {
        String value = text(json, path);
        if (!POSITIVE_ID.matcher(value).matches()) { throw error("RESULT_VALUE_INVALID", path, "ID 必须为正整数字符串"); }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw error("RESULT_VALUE_INVALID", path, "ID 超出 signed64 正整数范围");
        }
    }

    private void array(JsonNode json, String path) {
        if (json == null || !json.isArray() || json.isEmpty()) {
            throw error("RESULT_INVALID_DOCUMENT", path, "必须为非空数组");
        }
        if (json.size() > limits.maxChildren()) { throw error("LIMIT_EXCEEDED", path, "结果条目数超限"); }
    }

    private String text(JsonNode json, String path) {
        if (json == null || !json.isTextual() || json.textValue().isEmpty()) {
            throw error("RESULT_INVALID_DOCUMENT", path, "必须为非空字符串");
        }
        RuleDslValues.stringLimit(json.textValue(), path, limits);
        return json.textValue();
    }

    private void fields(JsonNode json, String path, Set<String> expected) {
        if (json == null || !json.isObject()) { throw error("RESULT_INVALID_DOCUMENT", path, "必须为对象"); }
        for (Map.Entry<String, JsonNode> entry : json.properties()) {
            if (!expected.contains(entry.getKey())) {
                throw error("RESULT_INVALID_DOCUMENT", path + "/" + RuleDslValidator.escape(entry.getKey()), "结果字段未登记");
            }
        }
        for (String field : expected) {
            if (!json.has(field)) { throw error("RESULT_INVALID_DOCUMENT", path + "/" + field, "结果字段缺失"); }
        }
    }
}
