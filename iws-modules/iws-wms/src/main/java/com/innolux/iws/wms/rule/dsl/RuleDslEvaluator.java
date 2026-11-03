/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL
 * @Version      : V1.0
 * @Description  : 先全量校验引用事实，再进行 Kleene 三值纯计算；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.innolux.iws.wms.rule.dsl.RuleDslValues.error;

/**
 * 条件预览内核，无持久化效果。绑定和事实快照均由受信调用方提供，事实只承载值及待核验单位，
 * 不能替换已编译的服务端绑定元数据。未来 HTTP 适配器另行校验客户端输入与授权来源。
 * 安全用途的强制 ALL 必须由未来业务适配器落实。
 */
public final class RuleDslEvaluator {
    /** 仅 TRUE 命中；UNKNOWN 不能通过 NOT 变成匹配。 */
    public enum Truth {
        TRUE, FALSE, UNKNOWN;

        public boolean matches() { return this == TRUE; }

        Truth not() { return this == UNKNOWN ? UNKNOWN : this == TRUE ? FALSE : TRUE; }
    }

    /**
     * 事实值必须使用相同的严格 JSON literal 编码。Map 缺键为 ABSENT，显式 NullNode 为 NULL。
     * SET 使用数组，允许 null 元素；unit 必须与冻结目录精确相同。构造时复制可变 JSON。
     */
    public record Fact(JsonNode value, String unit) {
        public Fact { value = value == null ? null : value.deepCopy(); }

        @Override
        public JsonNode value() { return value == null ? null : value.deepCopy(); }
    }

    private enum State { ABSENT, NULL, PRESENT }

    private record Value(State state, List<Object> elements) { }

    /** 不读取当前目录；预校验所有引用属性后才执行 AST，任何类型或单位错误直接失败。 */
    public Truth evaluate(RuleCondition condition, Map<String, Fact> facts) {
        Objects.requireNonNull(condition, "已编译条件必填");
        if (facts == null) { throw error("VALUE_INVALID", "/facts", "事实快照必填"); }
        Map<String, Value> values = new LinkedHashMap<>();
        for (Map.Entry<String, RuleAttributeBinding> entry : condition.bindings().entrySet()) {
            values.put(entry.getKey(), prepare(entry.getValue(), facts, condition.limits));
        }
        return evaluateNode(condition.root, values);
    }

    private Value prepare(RuleAttributeBinding binding, Map<String, Fact> facts, DslLimits limits) {
        String key = binding.attributeKey();
        String path = "/facts/" + RuleDslValidator.escape(key);
        if (!facts.containsKey(key)) { return new Value(State.ABSENT, List.of()); }
        Fact fact = facts.get(key);
        if (fact == null || fact.value == null || fact.value.isMissingNode()) {
            throw error("VALUE_INVALID", path, "已提供事实必须显式给出值，NULL 使用 NullNode");
        }
        if (!Objects.equals(binding.unit(), fact.unit())) {
            throw error("VALUE_INVALID", path + "/unit", "事实单位与冻结属性单位不一致");
        }
        if (fact.value.isNull()) { return new Value(State.NULL, List.of()); }
        if ("SCALAR".equals(binding.cardinality())) {
            return new Value(State.PRESENT, List.of(RuleDslValues.parse(fact.value, binding, path, limits)));
        }
        if (!fact.value.isArray()) { throw error("VALUE_INVALID", path, "SET 事实必须为数组"); }
        List<Object> elements = new ArrayList<>();
        for (int i = 0; i < fact.value.size(); i++) {
            JsonNode element = fact.value.get(i);
            elements.add(element.isNull() ? null : RuleDslValues.parse(element, binding, path + "/" + i, limits));
        }
        return new Value(State.PRESENT, Collections.unmodifiableList(elements));
    }

    private Truth evaluateNode(RuleCondition.Node node, Map<String, Value> values) {
        return switch (node) {
            case RuleCondition.Negation n -> evaluateNode(n.child(), values).not();
            case RuleCondition.Group g -> {
                List<Truth> results = g.children().stream().map(child -> evaluateNode(child, values)).toList();
                yield combine(results, g.and());
            }
            case RuleCondition.Predicate p -> predicate(p, values.get(p.binding().attributeKey()));
        };
    }

    private Truth predicate(RuleCondition.Predicate p, Value value) {
        if ("IS_NULL".equals(p.type())) {
            return value.state() == State.ABSENT ? Truth.UNKNOWN : value.state() == State.NULL ? Truth.TRUE : Truth.FALSE;
        }
        if (value.state() != State.PRESENT || value.elements().isEmpty()) { return Truth.UNKNOWN; }
        if ("SCALAR_COMPARE".equals(p.type())) {
            Object actual = value.elements().getFirst();
            if ("IN".equals(p.operator())) {
                return truth(p.literals().stream().anyMatch(literal -> RuleDslValues.compare(actual, literal) == 0));
            }
            return compare(actual, p.literals().getFirst(), p.operator());
        }
        List<Truth> results = value.elements().stream()
            .map(element -> element == null ? Truth.UNKNOWN : compare(element, p.literals().getFirst(), p.operator()))
            .toList();
        return combine(results, "ALL".equals(p.type()));
    }

    private Truth combine(List<Truth> results, boolean all) {
        if (results.isEmpty()) { return Truth.UNKNOWN; }
        if (results.contains(all ? Truth.FALSE : Truth.TRUE)) { return all ? Truth.FALSE : Truth.TRUE; }
        if (results.contains(Truth.UNKNOWN)) { return Truth.UNKNOWN; }
        return all ? Truth.TRUE : Truth.FALSE;
    }

    private Truth compare(Object actual, Object literal, String op) {
        int c = RuleDslValues.compare(actual, literal);
        return truth(switch (op) {
            case "EQ" -> c == 0;
            case "NE" -> c != 0;
            case "LT" -> c < 0;
            case "LE" -> c <= 0;
            case "GT" -> c > 0;
            case "GE" -> c >= 0;
            default -> throw new IllegalStateException("已编译 AST 含未登记操作符");
        });
    }

    private Truth truth(boolean value) { return value ? Truth.TRUE : Truth.FALSE; }
}
