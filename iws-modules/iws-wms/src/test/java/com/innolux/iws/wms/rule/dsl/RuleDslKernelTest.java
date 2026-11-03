/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL 测试
 * @Version      : V1.0
 * @Description  : 纯内存验证类型、三值、集合、绑定冻结与限额；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.innolux.iws.wms.rule.dsl.RuleDslEvaluator.Truth.*;
import static org.assertj.core.api.Assertions.*;

@Tag("local")
class RuleDslKernelTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> OPS = List.of("EQ", "NE", "LT", "LE", "GT", "GE", "IN", "IS_NULL");
    private final RuleDslValidator validator = new RuleDslValidator();
    private final RuleDslEvaluator evaluator = new RuleDslEvaluator();

    private RuleAttributeBinding binding(String type, String cardinality) {
        return new RuleAttributeBinding("a", "MATERIAL", type, cardinality,
            "BOOLEAN".equals(type) ? List.of("EQ", "NE", "IN", "IS_NULL") : OPS,
            "DECIMAL".equals(type) ? "kg" : null, "DECIMAL".equals(type) ? 6 : null,
            false, "ACTIVE", "ATTR-1", 1);
    }

    private JsonNode json(String value) { return JSON.readTree(value); }

    private String quote(String value) { return JSON.writeValueAsString(value); }

    private String predicate(String node, String op, String value) {
        return "{\"type\":\"" + node + "\",\"attribute\":\"a\",\"operator\":\"" + op + "\",\"value\":" + value + "}";
    }

    private String envelope(String root) {
        return "{\"schema\":\"iws.rule.condition/1\",\"root\":" + root + "}";
    }

    private RuleCondition compile(String root, RuleAttributeBinding b) {
        return validator.validate(envelope(root), Map.of("a", b));
    }

    private RuleDslEvaluator.Truth evaluate(String node, String type, String cardinality,
                                             String op, String literal, String fact) {
        RuleAttributeBinding b = binding(type, cardinality);
        RuleCondition condition = compile(predicate(node, op, literal), b);
        return evaluator.evaluate(condition, Map.of("a", new RuleDslEvaluator.Fact(json(fact), b.unit())));
    }

    private void invalid(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RuleDslException.class,
            e -> assertThat(e.code()).isEqualTo("RULE_DSL_" + code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0000000", "0.0000001", "100000000000000", "-100000000000000",
        "+1", "01", "1e2", " 1", "1.", ".1", "NaN"})
    void rejectsDecimalPrecisionAndSyntaxBeforeNormalization(String value) {
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", quote(value)), binding("DECIMAL", "SCALAR")), "VALUE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"99999999999999.999999", "-99999999999999.999999", "1.000000", "0.000000", "-0"})
    void acceptsDecimalBoundaries(String value) {
        assertThat(evaluate("SCALAR_COMPARE", "DECIMAL", "SCALAR", "EQ", quote(value), quote(value))).isEqualTo(TRUE);
    }

    @Test
    void decimalScaleIsBoundPerAttributeAndNoRoundingOccurs() {
        RuleAttributeBinding b = new RuleAttributeBinding("a", "MATERIAL", "DECIMAL", "SCALAR", OPS,
            "kg", 2, false, "ACTIVE", "ATTR-1", 1);
        compile(predicate("SCALAR_COMPARE", "EQ", "\"999999999999999999.99\""), b);
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "\"1.000\""), b), "VALUE_INVALID");
        assertThat(evaluate("SCALAR_COMPARE", "DECIMAL", "SCALAR", "EQ", "\"1.000000\"", "\"1\"")).isEqualTo(TRUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "1.0", "1e1", "01", "+1"})
    void rejectsInvalidSigned64Integer(String value) {
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", quote(value)), binding("INTEGER", "SCALAR")), "VALUE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775807", "-9223372036854775808"})
    void acceptsSigned64Bounds(String value) {
        assertThat(evaluate("SCALAR_COMPARE", "INTEGER", "SCALAR", "EQ", quote(value), quote(value))).isEqualTo(TRUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-02-29", "2026-02-30", "2026-13-01", "2026-1-01", "2026-10-29Z"})
    void rejectsInvalidStrictDates(String value) {
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", quote(value)), binding("DATE", "SCALAR")), "VALUE_INVALID");
    }

    @Test
    void comparesDatesAndOffsetInstants() {
        assertThat(evaluate("SCALAR_COMPARE", "DATE", "SCALAR", "LT", "\"2024-03-01\"", "\"2024-02-29\"")).isEqualTo(TRUE);
        assertThat(evaluate("SCALAR_COMPARE", "DATE_TIME", "SCALAR", "EQ", "\"2026-10-29T08:00:00+08:00\"",
            "\"2026-10-29T00:00:00Z\"")).isEqualTo(TRUE);
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "\"2026-10-29T08:00:00\""), binding("DATE_TIME", "SCALAR")), "VALUE_INVALID");
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "\"2026-02-30T08:00:00Z\""), binding("DATE_TIME", "SCALAR")), "VALUE_INVALID");
    }

    @Test
    void stringOrderUsesUnicodeCodePointsAndExactValues() {
        assertThat(evaluate("SCALAR_COMPARE", "STRING", "SCALAR", "GT", quote("\uE000"), quote("\uD800\uDC00"))).isEqualTo(TRUE);
        assertThat(evaluate("SCALAR_COMPARE", "STRING", "SCALAR", "EQ", quote("é"), quote("e\u0301"))).isEqualTo(FALSE);
        assertThat(evaluate("SCALAR_COMPARE", "STRING", "SCALAR", "EQ", quote("A"), quote("a"))).isEqualTo(FALSE);
        assertThat(evaluate("SCALAR_COMPARE", "STRING", "SCALAR", "EQ", quote("a"), quote("a "))).isEqualTo(FALSE);
    }

    @ParameterizedTest
    @CsvSource({"EQ,1,1,TRUE", "NE,1,2,TRUE", "LT,2,1,TRUE", "LE,1,1,TRUE", "GT,1,2,TRUE", "GE,2,2,TRUE"})
    void supportsComparisonMatrix(String op, String literal, String fact, String result) {
        assertThat(evaluate("SCALAR_COMPARE", "INTEGER", "SCALAR", op, quote(literal), quote(fact)).name()).isEqualTo(result);
    }

    @Test
    void validatesBooleanAndRejectsCoercion() {
        assertThat(evaluate("SCALAR_COMPARE", "BOOLEAN", "SCALAR", "EQ", "true", "true")).isEqualTo(TRUE);
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "\"true\""), binding("BOOLEAN", "SCALAR")), "VALUE_INVALID");
        invalid(() -> compile(predicate("SCALAR_COMPARE", "GT", "true"), binding("BOOLEAN", "SCALAR")), "OPERATOR_NOT_ALLOWED");
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "1"), binding("INTEGER", "SCALAR")), "VALUE_INVALID");
    }

    @Test
    void inIsScalarMembershipAndCannotBeUsedAsQuantifierOperator() {
        assertThat(evaluate("SCALAR_COMPARE", "INTEGER", "SCALAR", "IN", "[\"1\",\"2\"]", "\"2\"")).isEqualTo(TRUE);
        assertThat(evaluate("SCALAR_COMPARE", "INTEGER", "SCALAR", "IN", "[\"1\",\"2\"]", "\"3\"")).isEqualTo(FALSE);
        assertThat(evaluate("SCALAR_COMPARE", "INTEGER", "SCALAR", "IN", "[\"1\"]", "null")).isEqualTo(UNKNOWN);
        for (String value : List.of("[]", "[null]", "[\"1\",2]", "\"1\"")) {
            assertThatThrownBy(() -> compile(predicate("SCALAR_COMPARE", "IN", value), binding("INTEGER", "SCALAR")))
                .isInstanceOf(RuleDslException.class);
        }
        invalid(() -> compile(predicate("ANY", "IN", "[\"1\"]"), binding("INTEGER", "SET")), "OPERATOR_NOT_ALLOWED");
        invalid(() -> compile(predicate("ALL", "IN", "[\"1\"]"), binding("INTEGER", "SET")), "OPERATOR_NOT_ALLOWED");
    }

    @ParameterizedTest
    @CsvSource({"ANY,[],UNKNOWN", "ALL,[],UNKNOWN", "ANY,[null],UNKNOWN", "ALL,[null],UNKNOWN",
        "ANY,null,UNKNOWN", "ALL,null,UNKNOWN"})
    void emptyAndNullSetsRemainUnknownUnderNot(String node, String fact, String expected) {
        RuleCondition c = compile("{\"type\":\"NOT\",\"child\":" + predicate(node, "EQ", "\"x\"") + "}", binding("STRING", "SET"));
        assertThat(evaluator.evaluate(c, Map.of("a", new RuleDslEvaluator.Fact(json(fact), null))).name()).isEqualTo(expected);
        assertThat(evaluator.evaluate(c, Map.of())).isEqualTo(UNKNOWN);
    }

    @Test
    void quantifiersApplyKleeneSemanticsToEveryElement() {
        assertThat(evaluate("ANY", "STRING", "SET", "EQ", "\"x\"", "[null,\"x\"]")).isEqualTo(TRUE);
        assertThat(evaluate("ANY", "STRING", "SET", "EQ", "\"x\"", "[null,\"y\"]")).isEqualTo(UNKNOWN);
        assertThat(evaluate("ALL", "STRING", "SET", "EQ", "\"x\"", "[null,\"y\"]")).isEqualTo(FALSE);
        assertThat(evaluate("ALL", "STRING", "SET", "EQ", "\"x\"", "[null,\"x\"]")).isEqualTo(UNKNOWN);
        assertThat(evaluate("ALL", "STRING", "SET", "EQ", "\"x\"", "[\"x\",\"x\"]")).isEqualTo(TRUE);
        assertThat(evaluate("ANY", "STRING", "SET", "EQ", "\"x\"", "[\"y\",\"z\"]")).isEqualTo(FALSE);
    }

    @Test
    void absentNullEmptyStringAndNotAreDistinct() {
        String root = "{\"type\":\"IS_NULL\",\"attribute\":\"a\"}";
        RuleCondition c = compile(root, binding("STRING", "SCALAR"));
        assertThat(evaluator.evaluate(c, Map.of())).isEqualTo(UNKNOWN);
        assertThat(evaluator.evaluate(c, Map.of("a", new RuleDslEvaluator.Fact(json("null"), null)))).isEqualTo(TRUE);
        assertThat(evaluator.evaluate(c, Map.of("a", new RuleDslEvaluator.Fact(json("\"\""), null)))).isEqualTo(FALSE);
        RuleCondition not = compile("{\"type\":\"NOT\",\"child\":" + root + "}", binding("STRING", "SCALAR"));
        assertThat(evaluator.evaluate(not, Map.of())).isEqualTo(UNKNOWN);
        assertThat(evaluator.evaluate(not, Map.of("a", new RuleDslEvaluator.Fact(json("null"), null)))).isEqualTo(FALSE);
        assertThat(TRUE.matches()).isTrue();
        assertThat(FALSE.matches()).isFalse();
        assertThat(UNKNOWN.matches()).isFalse();
    }

    @Test
    void groupTruthTablesAndDoubleNot() {
        String a = predicate("SCALAR_COMPARE", "EQ", "\"x\"");
        String b = a.replace("\"a\"", "\"b\"");
        RuleAttributeBinding bb = new RuleAttributeBinding("b", "MATERIAL", "STRING", "SCALAR", OPS, null, null, false, "ACTIVE", "1", 1);
        for (String group : List.of("AND", "OR")) {
            RuleCondition c = validator.validate(envelope("{\"type\":\"" + group + "\",\"children\":[" + a + "," + b + "]}"),
                Map.of("a", binding("STRING", "SCALAR"), "b", bb));
            for (var av : RuleDslEvaluator.Truth.values()) {
                for (var bv : RuleDslEvaluator.Truth.values()) {
                    Map<String, RuleDslEvaluator.Fact> facts = new HashMap<>();
                    if (av != UNKNOWN) { facts.put("a", new RuleDslEvaluator.Fact(json(av == TRUE ? "\"x\"" : "\"y\""), null)); }
                    if (bv != UNKNOWN) { facts.put("b", new RuleDslEvaluator.Fact(json(bv == TRUE ? "\"x\"" : "\"y\""), null)); }
                    var expected = "AND".equals(group)
                        ? (av == FALSE || bv == FALSE ? FALSE : av == TRUE && bv == TRUE ? TRUE : UNKNOWN)
                        : (av == TRUE || bv == TRUE ? TRUE : av == FALSE && bv == FALSE ? FALSE : UNKNOWN);
                    assertThat(evaluator.evaluate(c, facts)).isEqualTo(expected);
                }
            }
        }
        RuleCondition twice = compile("{\"type\":\"NOT\",\"child\":{\"type\":\"NOT\",\"child\":" + a + "}}", binding("STRING", "SCALAR"));
        assertThat(evaluator.evaluate(twice, Map.of())).isEqualTo(UNKNOWN);
    }

    @Test
    void validatesAllReferencedFactsBeforeOrCanMatch() {
        RuleAttributeBinding b = new RuleAttributeBinding("b", "MATERIAL", "DECIMAL", "SCALAR", OPS, "kg", 6, false, "ACTIVE", "1", 1);
        String a = predicate("SCALAR_COMPARE", "EQ", "\"ok\"");
        String second = predicate("SCALAR_COMPARE", "EQ", "\"1\"").replace("\"a\"", "\"b\"");
        RuleCondition c = validator.validate(envelope("{\"type\":\"OR\",\"children\":[" + a + "," + second + "]}"),
            Map.of("a", binding("STRING", "SCALAR"), "b", b));
        for (var bad : List.of(new RuleDslEvaluator.Fact(json("\"1\""), "g"),
            new RuleDslEvaluator.Fact(json("1"), "kg"), new RuleDslEvaluator.Fact(json("\"1.0000000\""), "kg"))) {
            invalid(() -> evaluator.evaluate(c, Map.of("a", new RuleDslEvaluator.Fact(json("\"ok\""), null), "b", bad)), "VALUE_INVALID");
        }
        invalid(() -> evaluate("ANY", "INTEGER", "SET", "EQ", "\"1\"", "[\"1\",true]"), "VALUE_INVALID");
    }

    @Test
    void snapshotsCatalogAstAndFactsImmutably() {
        List<String> ops = new ArrayList<>(OPS);
        RuleAttributeBinding b = new RuleAttributeBinding("a", "MATERIAL", "STRING", "SCALAR", ops, null, null, false, "ACTIVE", "OLD", 1);
        Map<String, RuleAttributeBinding> catalog = new HashMap<>(Map.of("a", b));
        ObjectNode document = (ObjectNode) json(envelope(predicate("SCALAR_COMPARE", "EQ", "\"x\"")));
        RuleCondition c = validator.validate(document, catalog);
        ops.clear();
        catalog.clear();
        ((ObjectNode) document.get("root")).put("value", "changed");
        assertThat(c.bindings().get("a").contractVersion()).isEqualTo("OLD");
        assertThat(c.bindings().get("a").operators()).contains("EQ");
        assertThatThrownBy(() -> c.bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> b.operators().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(evaluator.evaluate(c, Map.of("a", new RuleDslEvaluator.Fact(json("\"x\""), null)))).isEqualTo(TRUE);
        ObjectNode mutable = (ObjectNode) json("{\"x\":1}");
        RuleDslEvaluator.Fact fact = new RuleDslEvaluator.Fact(mutable, null);
        mutable.put("x", 2);
        ((ObjectNode) fact.value()).put("x", 3);
        assertThat(fact.value().get("x").intValue()).isEqualTo(1);
    }

    @Test
    void directoryAllowsUnusedInButIsNullMustBeExplicitlyAllowed() {
        compile(predicate("SCALAR_COMPARE", "EQ", "\"x\""), binding("STRING", "SCALAR"));
        RuleAttributeBinding b = new RuleAttributeBinding("a", "MATERIAL", "STRING", "SCALAR", List.of("EQ", "IN"), null, null, false, "ACTIVE", "1", 1);
        invalid(() -> compile("{\"type\":\"IS_NULL\",\"attribute\":\"a\"}", b), "OPERATOR_NOT_ALLOWED");
    }

    @Test
    void rejectsUnknownInactiveAndInvalidBindings() {
        for (var b : List.of(
            new RuleAttributeBinding("a", "MATERIAL", "STRING", "SCALAR", OPS, null, null, false, "DISABLED", "1", 1),
            new RuleAttributeBinding("a", "MATERIAL", "STRING", "LIST", OPS, null, null, false, "ACTIVE", "1", 1),
            new RuleAttributeBinding("a", "MATERIAL", "STRING", "SCALAR", List.of("EQ", "EQ"), null, null, false, "ACTIVE", "1", 1),
            new RuleAttributeBinding("a", "MATERIAL", "STRING", "SCALAR", List.of("SQL"), null, null, false, "ACTIVE", "1", 1),
            new RuleAttributeBinding("a", "MATERIAL", "DECIMAL", "SCALAR", OPS, "kg", 7, false, "ACTIVE", "1", 1))) {
            invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "\"x\""), b), "BINDING_INVALID");
        }
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "\"x\""), binding("OBJECT", "SCALAR")), "UNKNOWN_VALUE_TYPE");
        invalid(() -> validator.validate(envelope(predicate("SCALAR_COMPARE", "EQ", "\"x\"")), Map.of()), "UNKNOWN_ATTRIBUTE");
        invalid(() -> compile(predicate("ANY", "EQ", "\"x\""), binding("STRING", "SCALAR")), "CARDINALITY_MISMATCH");
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "\"x\""), binding("STRING", "SET")), "CARDINALITY_MISMATCH");
    }

    @Test
    void rawJsonRejectsDuplicateKeysAndTrailingDocuments() {
        String valid = envelope(predicate("SCALAR_COMPARE", "EQ", "\"x\""));
        invalid(() -> validator.validate(valid.replace("\"value\":\"x\"", "\"value\":\"x\",\"value\":\"y\""), Map.of()), "INVALID_DOCUMENT");
        invalid(() -> validator.validate(valid.replace("\"schema\":", "\"schema\":\"wrong\",\"schema\":"), Map.of()), "INVALID_DOCUMENT");
        invalid(() -> validator.validate(valid + " {}", Map.of()), "INVALID_DOCUMENT");
    }

    @Test
    void nodeInputExplicitlyCannotRecoverPreviouslyDiscardedDuplicateKeys() {
        String duplicate = envelope(predicate("SCALAR_COMPARE", "EQ", "\"x\""))
            .replace("\"value\":\"x\"", "\"value\":\"y\",\"value\":\"x\"");
        RuleCondition c = validator.validate(JSON.readTree(duplicate), Map.of("a", binding("STRING", "SCALAR")));
        assertThat(evaluator.evaluate(c, Map.of("a", new RuleDslEvaluator.Fact(json("\"x\""), null)))).isEqualTo(TRUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sql", "script", "column", "valueType", "unit", "scale", "contractVersion", "unknown"})
    void rejectsEveryExtraField(String field) {
        ObjectNode root = (ObjectNode) json(predicate("SCALAR_COMPARE", "EQ", "\"x\""));
        root.put(field, "anything");
        invalid(() -> compile(root.toString(), binding("STRING", "SCALAR")), "INVALID_DOCUMENT");
    }

    @Test
    void rejectsMissingAndWrongShapeWithPointer() {
        invalid(() -> compile("{\"type\":\"IS_NULL\",\"attribute\":\"a\",\"value\":null}", binding("STRING", "SCALAR")), "INVALID_DOCUMENT");
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", "null"), binding("STRING", "SCALAR")), "VALUE_INVALID");
        invalid(() -> compile("{\"type\":\"EVAL\",\"script\":\"true\"}", binding("STRING", "SCALAR")), "INVALID_DOCUMENT");
        assertThatThrownBy(() -> compile("{\"type\":\"SCALAR_COMPARE\",\"attribute\":\"a\",\"operator\":\"EQ\"}", binding("STRING", "SCALAR")))
            .isInstanceOfSatisfying(RuleDslException.class, e -> assertThat(e.pointer()).isEqualTo("/root/value"));
    }

    @Test
    void limitsCountDepthNodesChildrenAndInEntriesIndependently() {
        String leaf = predicate("SCALAR_COMPARE", "EQ", "\"x\"");
        String deep = leaf;
        for (int i = 1; i < 16; i++) { deep = "{\"type\":\"NOT\",\"child\":" + deep + "}"; }
        compile(deep, binding("STRING", "SCALAR"));
        String tooDeep = "{\"type\":\"NOT\",\"child\":" + deep + "}";
        invalid(() -> compile(tooDeep, binding("STRING", "SCALAR")), "LIMIT_EXCEEDED");
        RuleDslValidator small = new RuleDslValidator(new DslLimits(16, 3, 2, 4096));
        String group = "{\"type\":\"AND\",\"children\":[" + leaf + "," + leaf + "]}";
        small.validate(envelope(group), Map.of("a", binding("STRING", "SCALAR")));
        invalid(() -> small.validate(envelope("{\"type\":\"NOT\",\"child\":" + group + "}"), Map.of("a", binding("STRING", "SCALAR"))), "LIMIT_EXCEEDED");
        invalid(() -> small.validate(envelope("{\"type\":\"OR\",\"children\":[" + leaf + "," + leaf + "," + leaf + "]}"), Map.of("a", binding("STRING", "SCALAR"))), "LIMIT_EXCEEDED");
        invalid(() -> small.validate(envelope(predicate("SCALAR_COMPARE", "IN", "[\"x\",\"y\",\"z\"]")), Map.of("a", binding("STRING", "SCALAR"))), "LIMIT_EXCEEDED");
    }

    @Test
    void defaultNodeCountBoundaryAndCodePointStringLimit() {
        String leaf = predicate("SCALAR_COMPARE", "EQ", "\"x\"");
        String group = "{\"type\":\"AND\",\"children\":[" + String.join(",", java.util.Collections.nCopies(31, leaf)) + "]}";
        String exact = "{\"type\":\"AND\",\"children\":[" + String.join(",", java.util.Collections.nCopies(7, group))
            + "," + "{\"type\":\"AND\",\"children\":[" + String.join(",", java.util.Collections.nCopies(30, leaf)) + "]}]}";
        compile(exact, binding("STRING", "SCALAR")); // 1 + 7*32 + 31 = 256
        invalid(() -> compile("{\"type\":\"NOT\",\"child\":" + exact + "}", binding("STRING", "SCALAR")), "LIMIT_EXCEEDED");
        String supplementary = "\uD800\uDC00".repeat(4096);
        assertThat(evaluate("SCALAR_COMPARE", "STRING", "SCALAR", "EQ", quote(supplementary), quote(supplementary))).isEqualTo(TRUE);
        invalid(() -> compile(predicate("SCALAR_COMPARE", "EQ", quote(supplementary + "x")), binding("STRING", "SCALAR")), "LIMIT_EXCEEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"select * from stock", "<script>alert(1)</script>", ""})
    void stringLiteralContentIsOnlyTextIncludingEmptyString(String value) {
        assertThat(evaluate("SCALAR_COMPARE", "STRING", "SCALAR", "EQ", quote(value), quote(value))).isEqualTo(TRUE);
        assertThat(evaluate("SCALAR_COMPARE", "STRING", "SCALAR", "EQ", quote(value), "null")).isEqualTo(UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {" SCALAR_COMPARE", "SCALAR_COMPARE ", "scalar_compare"})
    void nodeTypeIsExactWithoutTrim(String type) {
        invalid(() -> compile(predicate(type, "EQ", "\"x\""), binding("STRING", "SCALAR")), "INVALID_DOCUMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {" EQ", "EQ ", "eq"})
    void operatorIsExactWithoutTrim(String operator) {
        invalid(() -> compile(predicate("SCALAR_COMPARE", operator, "\"x\""), binding("STRING", "SCALAR")), "OPERATOR_NOT_ALLOWED");
    }

    @ParameterizedTest
    @ValueSource(strings = {" a", "a ", "A"})
    void attributeIsExactWithoutTrim(String attribute) {
        String root = predicate("SCALAR_COMPARE", "EQ", "\"x\"").replace("\"a\"", quote(attribute));
        invalid(() -> compile(root, binding("STRING", "SCALAR")), "UNKNOWN_ATTRIBUTE");
    }

    @Test
    void factPayloadCannotReplaceFrozenServerBindingMetadata() {
        RuleCondition c = compile(predicate("SCALAR_COMPARE", "EQ", "\"1\""), binding("INTEGER", "SCALAR"));
        var forged = new RuleDslEvaluator.Fact(json("{\"valueType\":\"STRING\",\"value\":\"1\"}"), null);
        invalid(() -> evaluator.evaluate(c, Map.of("a", forged)), "VALUE_INVALID");
        assertThat(c.bindings().get("a").valueType()).isEqualTo("INTEGER");
    }
}
