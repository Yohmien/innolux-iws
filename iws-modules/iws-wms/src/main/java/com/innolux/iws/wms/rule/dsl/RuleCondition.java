/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 条件 DSL
 * @Version      : V1.0
 * @Description  : 仅可由校验器构建的不可变 AST 与属性绑定；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import java.util.List;
import java.util.Map;

/** 校验器编译结果；不保存可变 JsonNode，也不重新读取目录。 */
public final class RuleCondition {
    final Node root;
    private final Map<String, RuleAttributeBinding> bindings;
    final DslLimits limits;

    RuleCondition(Node root, Map<String, RuleAttributeBinding> bindings, DslLimits limits) {
        this.root = root;
        this.bindings = Map.copyOf(bindings);
        this.limits = limits;
    }

    public Map<String, RuleAttributeBinding> bindings() { return bindings; }

    sealed interface Node permits Group, Negation, Predicate { }

    record Group(boolean and, List<Node> children) implements Node {
        Group { children = List.copyOf(children); }
    }

    record Negation(Node child) implements Node { }

    record Predicate(String type, RuleAttributeBinding binding, String operator,
                     List<Object> literals) implements Node {
        Predicate { literals = List.copyOf(literals); }
    }
}
