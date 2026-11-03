/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL
 * @Version      : V1.0
 * @Description  : 封闭条件语法编译与 ACTIVE 目录绑定；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.innolux.iws.wms.rule.dsl.RuleDslValues.error;

/**
 * 无数据库依赖。输入目录必须由受信调用方提供，输出仅包含被引用属性。
 * type/operator/attribute 精确匹配，不 trim。SQL/脚本入口由封闭语法拒绝，STRING 内容不做关键词过滤。
 */
public final class RuleDslValidator {
    public static final String SCHEMA = "iws.rule.condition/1";
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DslLimits limits;

    public RuleDslValidator() { this(DslLimits.DEFAULT); }

    public RuleDslValidator(DslLimits limits) { this.limits = java.util.Objects.requireNonNull(limits); }

    /** 原始 JSON 入口拒绝任意层级的重复 key 和尾随文档。 */
    public RuleCondition validate(String json, Map<String, RuleAttributeBinding> catalog) {
        if (json == null) { throw error("INVALID_DOCUMENT", "", "条件文档必填"); }
        JsonNode document;
        try {
            document = MAPPER.readTree(json);
        } catch (tools.jackson.core.JacksonException ex) {
            throw error("INVALID_DOCUMENT", "", "JSON 无效、重复 key 或超出解析器限制");
        }
        return validate(document, catalog);
    }

    /**
     * 树入口严格校验现存节点；上游解析丢失的重复 key 无法恢复或检测。
     * 需要重复 key 保证的调用方必须使用原始字符串入口或先用严格解析器解析。
     */
    public RuleCondition validate(JsonNode document, Map<String, RuleAttributeBinding> catalog) {
        fields(document, "", Set.of("schema", "root"));
        if (!SCHEMA.equals(text(document.get("schema"), "/schema"))) {
            throw error("INVALID_DOCUMENT", "/schema", "条件 schema 未登记");
        }
        if (catalog == null) { throw error("BINDING_INVALID", "", "属性目录必填"); }
        Map<String, RuleAttributeBinding> bindings = new LinkedHashMap<>();
        RuleCondition.Node root = node(document.get("root"), "/root", 1, new int[1], catalog, bindings);
        return new RuleCondition(root, bindings, limits);
    }

    private RuleCondition.Node node(JsonNode json, String path, int depth, int[] count,
                                    Map<String, RuleAttributeBinding> catalog,
                                    Map<String, RuleAttributeBinding> bindings) {
        if (depth > limits.maxDepth() || ++count[0] > limits.maxNodes()) {
            throw error("LIMIT_EXCEEDED", path, "条件树深度或节点总数超限");
        }
        if (json == null || !json.isObject()) { throw error("INVALID_DOCUMENT", path, "条件节点必须为对象"); }
        String type = text(json.get("type"), path + "/type");
        return switch (type) {
            case "AND", "OR" -> {
                fields(json, path, Set.of("type", "children"));
                JsonNode children = json.get("children");
                array(children, path + "/children", 2);
                List<RuleCondition.Node> nodes = new ArrayList<>();
                for (int i = 0; i < children.size(); i++) {
                    nodes.add(node(children.get(i), path + "/children/" + i, depth + 1, count, catalog, bindings));
                }
                yield new RuleCondition.Group("AND".equals(type), nodes);
            }
            case "NOT" -> {
                fields(json, path, Set.of("type", "child"));
                yield new RuleCondition.Negation(node(json.get("child"), path + "/child", depth + 1,
                    count, catalog, bindings));
            }
            case "SCALAR_COMPARE", "IS_NULL", "ANY", "ALL" -> predicate(json, type, path, catalog, bindings);
            default -> throw error("INVALID_DOCUMENT", path + "/type", "条件节点类型未登记");
        };
    }

    private RuleCondition.Predicate predicate(JsonNode json, String type, String path,
                                              Map<String, RuleAttributeBinding> catalog,
                                              Map<String, RuleAttributeBinding> bindings) {
        boolean isNull = "IS_NULL".equals(type);
        fields(json, path, isNull ? Set.of("type", "attribute") : Set.of("type", "attribute", "operator", "value"));
        String key = text(json.get("attribute"), path + "/attribute");
        RuleAttributeBinding binding = bindings.get(key);
        if (binding == null) {
            binding = catalog.get(key);
            if (binding == null) { throw error("UNKNOWN_ATTRIBUTE", path + "/attribute", "属性未登记"); }
            RuleDslValues.binding(binding, path + "/attribute", limits);
            if (!key.equals(binding.attributeKey())) {
                throw error("BINDING_INVALID", path + "/attribute", "目录键与绑定属性键不一致");
            }
            bindings.put(key, binding);
        }
        boolean scalar = "SCALAR_COMPARE".equals(type) || isNull;
        if (!(scalar ? "SCALAR" : "SET").equals(binding.cardinality())) {
            throw error("CARDINALITY_MISMATCH", path, "条件节点与属性基数不一致");
        }
        String op = isNull ? "IS_NULL" : text(json.get("operator"), path + "/operator");
        boolean nodeAllows = isNull || RuleDslValues.COMPARISONS.contains(op) || (scalar && "IN".equals(op));
        if (!nodeAllows || !RuleDslValues.allowed(binding.valueType(), op) || !binding.operators().contains(op)) {
            throw error("OPERATOR_NOT_ALLOWED", path + "/operator", "操作符不在节点、类型或目录允许范围");
        }
        List<Object> literals = new ArrayList<>();
        if ("IN".equals(op)) {
            JsonNode values = json.get("value");
            array(values, path + "/value", 1);
            for (int i = 0; i < values.size(); i++) {
                literals.add(RuleDslValues.parse(values.get(i), binding, path + "/value/" + i, limits));
            }
        } else if (!isNull) {
            literals.add(RuleDslValues.parse(json.get("value"), binding, path + "/value", limits));
        }
        return new RuleCondition.Predicate(type, binding, op, literals);
    }

    private void array(JsonNode json, String path, int min) {
        if (json == null || !json.isArray() || json.size() < min) {
            throw error("INVALID_DOCUMENT", path, "必须为至少 " + min + " 项的数组");
        }
        if (json.size() > limits.maxChildren()) { throw error("LIMIT_EXCEEDED", path, "数组条目数超限"); }
    }

    private String text(JsonNode json, String path) {
        if (json == null || !json.isTextual() || json.textValue().isEmpty()) {
            throw error("INVALID_DOCUMENT", path, "必须为非空字符串");
        }
        RuleDslValues.stringLimit(json.textValue(), path, limits);
        return json.textValue();
    }

    private static void fields(JsonNode json, String path, Set<String> expected) {
        if (json == null || !json.isObject()) { throw error("INVALID_DOCUMENT", path, "必须为对象"); }
        for (Map.Entry<String, JsonNode> entry : json.properties()) {
            if (!expected.contains(entry.getKey())) {
                throw error("INVALID_DOCUMENT", path + "/" + escape(entry.getKey()), "字段未登记");
            }
        }
        for (String field : expected) {
            if (!json.has(field)) { throw error("INVALID_DOCUMENT", path + "/" + field, "字段缺失"); }
        }
    }

    static String escape(String s) { return s.replace("~", "~0").replace("/", "~1"); }
}
