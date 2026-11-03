package com.innolux.iws.wms.rule.binding;

import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.rule.domain.WmsRuleAttributeRow;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.dsl.*;
import com.innolux.iws.wms.rule.mapper.WmsRuleAttributeMapper;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.rule.support.WmsRuleViewSupport;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

@Service
public final class WmsRuleBindingService {
    public static final String SCHEMA = "iws.rule.attribute-bindings/1";
    public static final String LIMITS_VERSION = "DSL-LOCAL-1";
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private static final Set<String> TYPES = Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATE_TIME");
    private static final Set<String> OPS = Set.of("EQ", "NE", "LT", "LE", "GT", "GE", "IN", "IS_NULL");
    private final WmsRuleAttributeMapper attributes;
    private final RuleAttributeSourceRegistry sources;
    private final RulePurposeRegistry purposes;
    private final RulePurposeSelection selections;

    public WmsRuleBindingService(WmsRuleAttributeMapper attributes, RuleAttributeSourceRegistry sources,
                                 RulePurposeRegistry purposes, RulePurposeSelection selections) {
        this.attributes = attributes;
        this.sources = sources;
        this.purposes = purposes;
        this.selections = selections;
    }

    public record Limits(String version, int maxDepth, int maxNodes, int maxChildren, int maxStringCodePoints) { }
    public record Snapshot(String schema, List<RuleAttributeBinding> bindings,
                           RulePurposeContract purposeContract, Limits limits) { }
    public record Compiled(String snapshotJson, String digest) { }

    public Compiled compile(WmsRuleDefinition definition, WmsRuleVersion version) {
        String purposeVersion = selections.requireExactVersion(
            definition.getPurpose(),
            definition.getScopeType()
        );
        RulePurposeContract purpose = purposes.resolveExact(
            definition.getPurpose(),
            purposeVersion,
            definition.getScopeType()
        );
        JsonNode condition = strict(version.getConditionJson());
        JsonNode result = strict(version.getResultJson());
        Set<String> keys = references(condition, result);
        Map<String, RuleAttributeBinding> bindings = new TreeMap<>();
        for (String key : keys) {
            WmsRuleAttributeRow row = attributes.selectExactForShare(key);
            if (row == null) { throw invalid("RULE_ATTRIBUTE_NOT_FOUND", "属性未登记"); }
            RuleAttributeBinding binding = fromRow(row);
            validateSource(binding);
            bindings.put(key, binding);
        }
        validateTyped(version, purpose, bindings);
        Snapshot snapshot = new Snapshot(SCHEMA, List.copyOf(bindings.values()), purpose, limits());
        String json = WmsRuleDigest.canonical(canonicalSnapshot(snapshot));
        String digest = WmsRuleDigest.ruleVersionDigest(condition, result, strict(json));
        return new Compiled(json, digest);
    }

    public Snapshot requireReady(WmsRuleVersion version, WmsRuleDefinition definition) {
        if (version.getAttributeBindingsJson() == null) {
            throw invalid("RULE_BINDING_SNAPSHOT_MISSING", "历史规则未绑定，仅可读取");
        }
        try {
            JsonNode raw = strict(version.getAttributeBindingsJson());
            Snapshot snapshot = MAPPER.treeToValue(raw, Snapshot.class);
            if (!SCHEMA.equals(snapshot.schema()) || snapshot.bindings() == null || snapshot.bindings().isEmpty()
                || !limits().equals(snapshot.limits())) {
                throw invalid("RULE_BINDING_SNAPSHOT_INVALID", "快照 schema 或 limits 精确合同无效");
            }
            RulePurposeContract purpose = snapshot.purposeContract();
            if (purpose == null || !purpose.equals(purposes.resolveExact(purpose.purpose(), purpose.version(), purpose.scopeType()))) {
                throw invalid("RULE_BINDING_SNAPSHOT_INVALID", "用途精确合同不匹配");
            }
            if (definition != null && (!definition.getPurpose().equals(purpose.purpose())
                || !definition.getScopeType().equals(purpose.scopeType()))) {
                throw invalid("RULE_BINDING_SNAPSHOT_INVALID", "快照与规则定义用途不一致");
            }
            Map<String, RuleAttributeBinding> bindings = new TreeMap<>();
            for (RuleAttributeBinding binding : snapshot.bindings()) {
                validateSource(binding);
                RuleAttributeBinding normalized = canonicalBinding(binding);
                if (bindings.putIfAbsent(binding.attributeKey(), normalized) != null) {
                    throw invalid("RULE_BINDING_SNAPSHOT_INVALID", "快照属性重复");
                }
            }
            validateTyped(version, purpose, bindings);
            Snapshot canonical = new Snapshot(SCHEMA, List.copyOf(bindings.values()), purpose, limits());
            if (!WmsRuleDigest.canonical(raw).equals(
                WmsRuleDigest.canonical(canonicalSnapshot(canonical)))) {
                throw invalid("RULE_BINDING_SNAPSHOT_INVALID", "快照未按合同规范保存");
            }
            String digest = WmsRuleDigest.ruleVersionDigest(strict(version.getConditionJson()),
                strict(version.getResultJson()), raw);
            if (!digest.equals(version.getCanonicalDigest())) {
                throw invalid("RULE_DIGEST_MISMATCH", "规则正文或绑定快照摘要不一致");
            }
            return snapshot;
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid("RULE_BINDING_SNAPSHOT_INVALID", "绑定快照无效");
        }
    }

    public WmsRuleVersionVo toVersionVo(WmsRuleVersion version) {
        if (version == null) { return null; }
        String state = "LEGACY_UNBOUND";
        boolean ready = false;
        if (version.getAttributeBindingsJson() != null) {
            try {
                requireReady(version, null);
                state = "READY";
                ready = "ACTIVE".equals(version.getStatus());
            } catch (RuntimeException error) {
                state = "INVALID";
            }
        }
        return WmsRuleViewSupport.toVersionVo(version, state, ready);
    }

    private static JsonNode canonicalSnapshot(Snapshot snapshot) {
        List<Map<String, Object>> bindings = snapshot.bindings().stream()
            .sorted(Comparator.comparing(RuleAttributeBinding::attributeKey))
            .map(WmsRuleBindingService::bindingMap)
            .toList();
        RulePurposeContract purpose = snapshot.purposeContract();
        Map<String, Object> purposeMap = new LinkedHashMap<>();
        purposeMap.put("purpose", purpose.purpose());
        purposeMap.put("version", purpose.version());
        purposeMap.put("scopeType", purpose.scopeType());
        purposeMap.put("resultType", purpose.resultType().name());
        purposeMap.put("allowedTargetTypes", sorted(purpose.allowedTargetTypes()));
        purposeMap.put("sortableAttributes", sorted(purpose.sortableAttributes()));

        Map<String, Object> limitsMap = new LinkedHashMap<>();
        limitsMap.put("version", snapshot.limits().version());
        limitsMap.put("maxDepth", snapshot.limits().maxDepth());
        limitsMap.put("maxNodes", snapshot.limits().maxNodes());
        limitsMap.put("maxChildren", snapshot.limits().maxChildren());
        limitsMap.put("maxStringCodePoints", snapshot.limits().maxStringCodePoints());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", snapshot.schema());
        document.put("bindings", bindings);
        document.put("purposeContract", purposeMap);
        document.put("limits", limitsMap);
        return MAPPER.valueToTree(document);
    }

    private static Map<String, Object> bindingMap(RuleAttributeBinding binding) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("attributeKey", binding.attributeKey());
        row.put("sourceType", binding.sourceType());
        row.put("valueType", binding.valueType());
        row.put("cardinality", binding.cardinality());
        row.put("operators", binding.operators().stream().sorted().toList());
        row.put("unit", binding.unit());
        row.put("scale", binding.scale());
        row.put("groupable", binding.groupable());
        row.put("status", binding.status());
        row.put("contractVersion", binding.contractVersion());
        row.put("revision", binding.revision());
        return row;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private void validateTyped(WmsRuleVersion version, RulePurposeContract purpose,
                               Map<String, RuleAttributeBinding> bindings) {
        RuleCondition condition = new RuleDslValidator(DslLimits.DEFAULT).validate(version.getConditionJson(), bindings);
        RuleResult result = new RuleResultValidator(DslLimits.DEFAULT).validate(version.getResultJson(), purpose, bindings);
        Set<String> referenced = new TreeSet<>(condition.bindings().keySet());
        referenced.addAll(result.bindings().keySet());
        if (!referenced.equals(bindings.keySet())) {
            throw invalid("RULE_BINDING_SNAPSHOT_INVALID", "快照属性与条件及排序引用并集不一致");
        }
    }

    private void validateSource(RuleAttributeBinding binding) {
        RuleAttributeBinding normalized = canonicalBinding(binding);
        RuleAttributeBinding supported = sources.resolveExact(binding.sourceType(), binding.contractVersion())
            .bindings().get(binding.attributeKey());
        if (supported == null) { throw invalid("RULE_SOURCE_ATTRIBUTE_NOT_SUPPORTED", "来源未实现该属性"); }
        RuleAttributeBinding contract = canonicalBinding(supported);
        RuleAttributeBinding expected = new RuleAttributeBinding(contract.attributeKey(), contract.sourceType(),
            contract.valueType(), contract.cardinality(), contract.operators(), contract.unit(), contract.scale(),
            contract.groupable(), normalized.status(), contract.contractVersion(), normalized.revision());
        if (!normalized.equals(expected)) {
            throw invalid("RULE_SOURCE_CONTRACT_MISMATCH", "属性目录与来源合同不匹配");
        }
    }

    private RuleAttributeBinding fromRow(WmsRuleAttributeRow row) {
        JsonNode operators = strict(row.getOperatorsJson());
        if (!operators.isArray()) { throw invalid("BINDING_INVALID", "操作符必须为数组"); }
        List<String> raw = new ArrayList<>();
        for (JsonNode operator : operators) {
            if (!operator.isTextual()) { throw invalid("BINDING_INVALID", "操作符必须为字符串"); }
            raw.add(operator.textValue());
        }
        if (row.getRevision() == null || row.getGroupable() == null) {
            throw invalid("BINDING_INVALID", "目录合同字段缺失");
        }
        return canonicalBinding(new RuleAttributeBinding(row.getAttributeKey(), row.getSourceType(), row.getValueType(),
            row.getCardinality(), raw, row.getUnit(), row.getScale(), row.getGroupable(), row.getStatus(),
            row.getContractVersion(), row.getRevision()));
    }

    private static RuleAttributeBinding canonicalBinding(RuleAttributeBinding binding) {
        if (binding == null || binding.attributeKey() == null || binding.attributeKey().isBlank()
            || binding.sourceType() == null || binding.sourceType().isBlank()
            || binding.contractVersion() == null || binding.contractVersion().isBlank()
            || !"ACTIVE".equals(binding.status()) || binding.revision() < 1
            || !TYPES.contains(binding.valueType() == null ? "" : binding.valueType())
            || !Set.of("SCALAR", "SET").contains(binding.cardinality() == null ? "" : binding.cardinality())
            || binding.operators() == null || binding.operators().isEmpty()) {
            throw invalid("BINDING_INVALID", "属性合同不完整或未启用");
        }
        Set<String> seen = new HashSet<>();
        for (String operator : binding.operators()) {
            if (operator == null || !seen.add(operator) || !OPS.contains(operator)
                || ("BOOLEAN".equals(binding.valueType()) && !Set.of("EQ", "NE", "IN", "IS_NULL").contains(operator))) {
                throw invalid("BINDING_INVALID", "目录操作符重复、非法或不适用于该类型");
            }
        }
        if ("DECIMAL".equals(binding.valueType())) {
            if (binding.scale() == null || binding.scale() < 0 || binding.scale() > 6) {
                throw invalid("BINDING_INVALID", "DECIMAL 仅支持 precision=20、scale=0..6");
            }
        } else if (binding.scale() != null || binding.unit() != null) {
            throw invalid("BINDING_INVALID", "非 DECIMAL 单位和 scale 必须为 NULL");
        }
        return new RuleAttributeBinding(binding.attributeKey(), binding.sourceType(), binding.valueType(),
            binding.cardinality(), binding.operators().stream().sorted().toList(), binding.unit(), binding.scale(),
            binding.groupable(), binding.status(), binding.contractVersion(), binding.revision());
    }

    private static Set<String> references(JsonNode condition, JsonNode result) {
        Set<String> keys = new TreeSet<>();
        collect(condition.get("root"), keys, 1, new int[1]);
        if ("SORT".equals(result.path("type").asText())) {
            JsonNode sortKeys = result.get("keys");
            if (sortKeys == null || !sortKeys.isArray()) { throw invalid("RESULT_INVALID_DOCUMENT", "排序键必须为数组"); }
            for (JsonNode key : sortKeys) { keys.add(text(key.get("attribute"))); }
        }
        return keys;
    }

    private static void collect(JsonNode node, Set<String> keys, int depth, int[] count) {
        if (node == null || !node.isObject() || depth > DslLimits.DEFAULT.maxDepth()
            || ++count[0] > DslLimits.DEFAULT.maxNodes()) {
            throw invalid("INVALID_DOCUMENT", "条件节点无效或超出限额");
        }
        String type = text(node.get("type"));
        if ("AND".equals(type) || "OR".equals(type)) {
            JsonNode children = node.get("children");
            if (children == null || !children.isArray()) { throw invalid("INVALID_DOCUMENT", "条件 children 无效"); }
            for (JsonNode child : children) { collect(child, keys, depth + 1, count); }
        } else if ("NOT".equals(type)) {
            collect(node.get("child"), keys, depth + 1, count);
        } else {
            keys.add(text(node.get("attribute")));
        }
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("INVALID_DOCUMENT", "属性键或节点类型必须为非空文本");
        }
        return value.textValue();
    }

    private static Limits limits() {
        DslLimits limits = DslLimits.DEFAULT;
        return new Limits(LIMITS_VERSION, limits.maxDepth(), limits.maxNodes(), limits.maxChildren(), limits.maxStringCodePoints());
    }

    private static JsonNode strict(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isNull()) { throw invalid("INVALID_DOCUMENT", "JSON 文档必填"); }
            return node;
        } catch (ServiceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid("INVALID_DOCUMENT", "JSON 无效、重复 key 或含尾随内容");
        }
    }

    private static ServiceException invalid(String code, String message) {
        return new ServiceException(code + ": " + message, HttpStatus.HTTP_CONFLICT);
    }
}
