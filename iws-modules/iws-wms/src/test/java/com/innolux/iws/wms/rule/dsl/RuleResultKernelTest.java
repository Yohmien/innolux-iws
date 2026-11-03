/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 结果 DSL 测试
 * @Version      : V1.0
 * @Description  : 显式 fixture 用途及封闭结果结构的纯单元测试；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@Tag("local")
class RuleResultKernelTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FILTER = "{\"schema\":\"iws.rule.result/1\",\"type\":\"FILTER\",\"value\":true}";
    private static final String TARGET_PREFIX = "{\"schema\":\"iws.rule.result/1\",\"type\":\"TARGET_SET\",\"objectType\":\"FIXTURE_OBJECT\",\"ids\":";
    private final RuleResultValidator validator = new RuleResultValidator();

    private RulePurposeContract contract(RuleResult.Type type) {
        return new RulePurposeContract("FIXTURE_" + type.name(), "1", "FIXTURE_SCOPE", type,
            type == RuleResult.Type.TARGET_SET ? Set.of("FIXTURE_OBJECT") : Set.of(),
            type == RuleResult.Type.SORT ? Set.of("a", "b") : Set.of());
    }

    private RuleAttributeBinding binding(String key, String type, String cardinality) {
        return new RuleAttributeBinding(key, "FIXTURE_SOURCE", type, cardinality, List.of("EQ", "IS_NULL"),
            "DECIMAL".equals(type) ? "FIXTURE_UNIT" : null, "DECIMAL".equals(type) ? 6 : null,
            true, "ACTIVE", "FIXTURE_ATTR_1", 1);
    }

    private JsonNode json(String text) { return JSON.readTree(text); }

    private String sort(String keys) {
        return "{\"schema\":\"iws.rule.result/1\",\"type\":\"SORT\",\"keys\":" + keys + "}";
    }

    private String key(String attribute, String direction, String nulls) {
        return "{\"attribute\":" + JSON.writeValueAsString(attribute) + ",\"direction\":"
            + JSON.writeValueAsString(direction) + ",\"nulls\":" + JSON.writeValueAsString(nulls) + "}";
    }

    private void rejects(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RuleDslException.class,
            e -> assertThat(e.code()).isEqualTo("RULE_DSL_" + code));
    }

    @Test
    void filterPreservesBothBooleanValuesWithoutImplicitExecution() {
        for (boolean value : List.of(true, false)) {
            RuleResult r = validator.validate(FILTER.replace("true", Boolean.toString(value)), contract(RuleResult.Type.FILTER), Map.of());
            assertThat(r.type()).isEqualTo(RuleResult.Type.FILTER);
            assertThat(((RuleResult.Filter) r.payload()).value()).isEqualTo(value);
            assertThat(r.bindings()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "\"true\"", "[]", "{}"})
    void filterRejectsNonBooleanWithoutCoercion(String value) {
        rejects(() -> validator.validate(FILTER.replace("true", value), contract(RuleResult.Type.FILTER), Map.of()), "RESULT_VALUE_INVALID");
    }

    @Test
    void targetsSortNumericallyAndRetainPositiveSigned64Ids() {
        RuleResult r = validator.validate(TARGET_PREFIX + "[\"10\",\"9223372036854775807\",\"2\",\"1\"]}",
            contract(RuleResult.Type.TARGET_SET), Map.of());
        RuleResult.TargetSet target = (RuleResult.TargetSet) r.payload();
        assertThat(target.objectType()).isEqualTo("FIXTURE_OBJECT");
        assertThat(target.ids()).containsExactly(1L, 2L, 10L, Long.MAX_VALUE);
        assertThatThrownBy(() -> target.ids().add(3L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"0\"", "\"-1\"", "\"+1\"", "\"01\"", "\"1e2\"", "\"1.0\"", "\" 1\"",
        "\"1 \"", "\"9223372036854775808\"", "\"１\""})
    void targetsRejectNonPositiveOrNonCanonicalIds(String value) {
        rejects(() -> validator.validate(TARGET_PREFIX + "[" + value + "]}", contract(RuleResult.Type.TARGET_SET), Map.of()), "RESULT_VALUE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "true", "{}", "[]", "\"\""})
    void targetsRejectWrongLiteralTypes(String value) {
        rejects(() -> validator.validate(TARGET_PREFIX + "[" + value + "]}", contract(RuleResult.Type.TARGET_SET), Map.of()), "RESULT_INVALID_DOCUMENT");
    }

    @Test
    void targetsRejectDuplicatesEmptyArraysAndUnapprovedTypes() {
        rejects(() -> validator.validate(TARGET_PREFIX + "[\"1\",\"1\"]}", contract(RuleResult.Type.TARGET_SET), Map.of()), "RESULT_VALUE_INVALID");
        rejects(() -> validator.validate(TARGET_PREFIX + "[]}", contract(RuleResult.Type.TARGET_SET), Map.of()), "RESULT_INVALID_DOCUMENT");
        rejects(() -> validator.validate((TARGET_PREFIX + "[\"1\"]}").replace("FIXTURE_OBJECT", "OTHER"),
            contract(RuleResult.Type.TARGET_SET), Map.of()), "RESULT_TARGET_TYPE_NOT_ALLOWED");
        RulePurposeContract empty = new RulePurposeContract("FIXTURE", "1", "S", RuleResult.Type.TARGET_SET, Set.of(), Set.of());
        rejects(() -> validator.validate(TARGET_PREFIX + "[\"1\"]}", empty, Map.of()), "RESULT_TARGET_TYPE_NOT_ALLOWED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STRING", "DECIMAL", "INTEGER", "BOOLEAN", "DATE", "DATE_TIME"})
    void sortAcceptsOnlyExplicitlyAllowedBoundComparableScalarTypes(String type) {
        RuleResult r = validator.validate(sort("[" + key("a", "DESC", "FIRST") + "]"), contract(RuleResult.Type.SORT),
            Map.of("a", binding("a", type, "SCALAR")));
        RuleResult.SortKey first = ((RuleResult.Sort) r.payload()).keys().getFirst();
        assertThat(first.direction()).isEqualTo(RuleResult.Direction.DESC);
        assertThat(first.nulls()).isEqualTo(RuleResult.Nulls.FIRST);
        assertThat(r.bindings().get("a").valueType()).isEqualTo(type);
    }

    @Test
    void sortPreservesKeyOrderAndRejectsRepeatedAttributes() {
        String a = key("a", "ASC", "LAST");
        String b = key("b", "DESC", "FIRST");
        Map<String, RuleAttributeBinding> bindings = Map.of("a", binding("a", "STRING", "SCALAR"), "b", binding("b", "INTEGER", "SCALAR"));
        RuleResult r = validator.validate(sort("[" + b + "," + a + "]"), contract(RuleResult.Type.SORT), bindings);
        assertThat(((RuleResult.Sort) r.payload()).keys()).extracting(RuleResult.SortKey::attribute).containsExactly("b", "a");
        rejects(() -> validator.validate(sort("[" + a + "," + a + "]"), contract(RuleResult.Type.SORT), bindings), "RESULT_VALUE_INVALID");
    }

    @Test
    void catalogRegistrationAndGroupableDoNotImplySortable() {
        RulePurposeContract empty = new RulePurposeContract("FIXTURE", "1", "S", RuleResult.Type.SORT, Set.of(), Set.of());
        rejects(() -> validator.validate(sort("[" + key("a", "ASC", "LAST") + "]"), empty,
            Map.of("a", binding("a", "STRING", "SCALAR"))), "RESULT_SORT_NOT_ALLOWED");
        rejects(() -> validator.validate(sort("[" + key("other", "ASC", "LAST") + "]"), contract(RuleResult.Type.SORT),
            Map.of("other", binding("other", "STRING", "SCALAR"))), "RESULT_SORT_NOT_ALLOWED");
    }

    @Test
    void sortRejectsSetUnknownTypeMissingAndMismatchedBindings() {
        String input = sort("[" + key("a", "ASC", "LAST") + "]");
        rejects(() -> validator.validate(input, contract(RuleResult.Type.SORT), Map.of("a", binding("a", "STRING", "SET"))), "CARDINALITY_MISMATCH");
        rejects(() -> validator.validate(input, contract(RuleResult.Type.SORT), Map.of("a", binding("a", "OBJECT", "SCALAR"))), "UNKNOWN_VALUE_TYPE");
        rejects(() -> validator.validate(input, contract(RuleResult.Type.SORT), Map.of()), "BINDING_INVALID");
        rejects(() -> validator.validate(input, contract(RuleResult.Type.SORT), Map.of("a", binding("b", "STRING", "SCALAR"))), "BINDING_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", " ASC", "DESC ", "SQL", ""})
    void sortDirectionIsExact(String direction) {
        assertThatThrownBy(() -> validator.validate(sort("[" + key("a", direction, "LAST") + "]"), contract(RuleResult.Type.SORT),
            Map.of("a", binding("a", "STRING", "SCALAR")))).isInstanceOf(RuleDslException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", " FIRST", "LAST ", "AUTO", ""})
    void sortNullPlacementIsExact(String nulls) {
        assertThatThrownBy(() -> validator.validate(sort("[" + key("a", "ASC", nulls) + "]"), contract(RuleResult.Type.SORT),
            Map.of("a", binding("a", "STRING", "SCALAR")))).isInstanceOf(RuleDslException.class);
    }

    @Test
    void registryRejectsDuplicatesAndNeverSelectsHighestVersion() {
        RulePurposeContract first = contract(RuleResult.Type.FILTER);
        RulePurposeContract second = new RulePurposeContract(first.purpose(), "2", first.scopeType(), first.resultType(), Set.of(), Set.of());
        rejects(() -> new RulePurposeRegistry(List.of(first, first)), "RESULT_CONTRACT_DUPLICATE");
        RulePurposeRegistry registry = new RulePurposeRegistry(List.of(first, second));
        assertThat(registry.resolveExact(first.purpose(), "1", first.scopeType())).isEqualTo(first);
        assertThat(registry.resolveForActivation(first.purpose(), first.scopeType(), Map.of(first.purpose(), "2"))).isEqualTo(second);
        rejects(() -> registry.resolveForActivation(first.purpose(), first.scopeType(), Map.of()), "RESULT_VERSION_UNSELECTED");
        rejects(() -> registry.resolveExact(first.purpose(), "3", first.scopeType()), "RESULT_CONTRACT_NOT_FOUND");
        rejects(() -> registry.resolveForActivation(first.purpose(), first.scopeType(), Map.of(first.purpose(), "3")), "RESULT_CONTRACT_NOT_FOUND");
        rejects(() -> registry.resolveExact(first.purpose(), "1", "OTHER_SCOPE"), "RESULT_SCOPE_MISMATCH");
        rejects(() -> registry.resolveExact("UNKNOWN", "1", first.scopeType()), "RESULT_CONTRACT_NOT_FOUND");
        rejects(() -> registry.resolveExact(first.purpose() + " ", "1", first.scopeType()), "RESULT_CONTRACT_NOT_FOUND");
        rejects(() -> registry.resolveExact(first.purpose(), " 1", first.scopeType()), "RESULT_CONTRACT_NOT_FOUND");
    }

    @Test
    void registryAndCompiledResultRetainImmutableSnapshots() {
        Set<String> whitelist = new HashSet<>(Set.of("a"));
        RulePurposeContract c = new RulePurposeContract("FIXTURE", "1", "S", RuleResult.Type.SORT, Set.of(), whitelist);
        List<RulePurposeContract> entries = new ArrayList<>(List.of(c));
        RulePurposeRegistry registry = new RulePurposeRegistry(entries);
        Map<String, RuleAttributeBinding> bindings = new HashMap<>(Map.of("a", binding("a", "STRING", "SCALAR")));
        ObjectNode doc = (ObjectNode) json(sort("[" + key("a", "ASC", "LAST") + "]"));
        RuleResult r = validator.validate(doc, registry.resolveExact("FIXTURE", "1", "S"), bindings);
        whitelist.clear(); entries.clear(); bindings.clear();
        ((ObjectNode) doc.get("keys").get(0)).put("direction", "DESC");
        assertThat(r.purposeContract().sortableAttributes()).containsExactly("a");
        assertThat(((RuleResult.Sort) r.payload()).keys().getFirst().direction()).isEqualTo(RuleResult.Direction.ASC);
        assertThat(r.bindings().get("a").contractVersion()).isEqualTo("FIXTURE_ATTR_1");
        assertThatThrownBy(() -> r.bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.sortableAttributes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((RuleResult.Sort) r.payload()).keys().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onePurposeAllowsOnlyItsSingleResultType() {
        rejects(() -> validator.validate(TARGET_PREFIX + "[\"1\"]}", contract(RuleResult.Type.FILTER), Map.of()), "RESULT_TYPE_MISMATCH");
        rejects(() -> validator.validate(FILTER.replace("FILTER", "FILTER "), contract(RuleResult.Type.FILTER), Map.of()), "RESULT_TYPE_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"purpose", "version", "bindings", "sourceType", "sql", "script", "unknown"})
    void clientCannotProvideContractOrExecutionFields(String field) {
        ObjectNode doc = (ObjectNode) json(FILTER);
        doc.put(field, "select * from x");
        rejects(() -> validator.validate(doc, contract(RuleResult.Type.FILTER), Map.of()), "RESULT_INVALID_DOCUMENT");
    }

    @Test
    void rawJsonRejectsDuplicateRootNestedFieldsAndTrailingDocuments() {
        rejects(() -> validator.validate(FILTER.replace("\"value\":true", "\"value\":false,\"value\":true"),
            contract(RuleResult.Type.FILTER), Map.of()), "RESULT_INVALID_DOCUMENT");
        String duplicate = sort("[" + key("a", "ASC", "LAST").replace("\"direction\":\"ASC\"", "\"direction\":\"ASC\",\"direction\":\"DESC\"") + "]");
        rejects(() -> validator.validate(duplicate, contract(RuleResult.Type.SORT), Map.of()), "RESULT_INVALID_DOCUMENT");
        rejects(() -> validator.validate(FILTER + " {}", contract(RuleResult.Type.FILTER), Map.of()), "RESULT_INVALID_DOCUMENT");
        // 树入口只能看到上游已解析的结果，无法恢复丢失的重复 key。
        RuleResult r = validator.validate(json(FILTER.replace("\"value\":true", "\"value\":false,\"value\":true")),
            contract(RuleResult.Type.FILTER), Map.of());
        assertThat(((RuleResult.Filter) r.payload()).value()).isTrue();
    }

    @Test
    void arraysAreBoundedAndMissingNestedFieldsHavePointers() {
        RuleResultValidator small = new RuleResultValidator(new DslLimits(16, 256, 2, 4096));
        small.validate(TARGET_PREFIX + "[\"1\",\"2\"]}", contract(RuleResult.Type.TARGET_SET), Map.of());
        rejects(() -> small.validate(TARGET_PREFIX + "[\"1\",\"2\",\"3\"]}", contract(RuleResult.Type.TARGET_SET), Map.of()), "LIMIT_EXCEEDED");
        rejects(() -> validator.validate(sort("[]"), contract(RuleResult.Type.SORT), Map.of()), "RESULT_INVALID_DOCUMENT");
        String incomplete = sort("[{\"attribute\":\"a\",\"direction\":\"ASC\"}]");
        assertThatThrownBy(() -> validator.validate(incomplete, contract(RuleResult.Type.SORT), Map.of()))
            .isInstanceOfSatisfying(RuleDslException.class, e -> assertThat(e.pointer()).isEqualTo("/keys/0/nulls"));
    }

    @Test
    void stringLimitsUseCodePointsAndIdentifiersDoNotTrim() {
        String unicode = "\uD800\uDC00".repeat(4096);
        RulePurposeContract c = new RulePurposeContract("FIXTURE", "1", "S", RuleResult.Type.TARGET_SET, Set.of(unicode), Set.of());
        validator.validate((TARGET_PREFIX + "[\"1\"]}").replace("FIXTURE_OBJECT", unicode), c, Map.of());
        rejects(() -> validator.validate((TARGET_PREFIX + "[\"1\"]}").replace("FIXTURE_OBJECT", unicode + "a"), c, Map.of()), "LIMIT_EXCEEDED");
        rejects(() -> validator.validate((TARGET_PREFIX + "[\"1\"]}").replace("FIXTURE_OBJECT", "FIXTURE_OBJECT "),
            contract(RuleResult.Type.TARGET_SET), Map.of()), "RESULT_TARGET_TYPE_NOT_ALLOWED");
        rejects(() -> validator.validate(sort("[" + key(" a", "ASC", "LAST") + "]"), contract(RuleResult.Type.SORT), Map.of()), "RESULT_SORT_NOT_ALLOWED");
    }
}
