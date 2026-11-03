/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 结果 DSL
 * @Version      : V1.0
 * @Description  : 不可变 typed 结果及用途合同与排序属性快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.dsl;

import java.util.List;
import java.util.Map;

/** 仅表示结构及受信合同校验完成，不证明目标存在、授权有效或已具备发布条件。 */
public final class RuleResult {
    public enum Type { FILTER, TARGET_SET, SORT }
    public enum Direction { ASC, DESC }
    public enum Nulls { FIRST, LAST }

    public sealed interface Payload permits Filter, TargetSet, Sort { }
    public record Filter(boolean value) implements Payload { }
    public record TargetSet(String objectType, List<Long> ids) implements Payload {
        public TargetSet { ids = List.copyOf(ids); }
    }
    public record SortKey(String attribute, Direction direction, Nulls nulls) { }
    public record Sort(List<SortKey> keys) implements Payload {
        public Sort { keys = List.copyOf(keys); }
    }

    private final RulePurposeContract purposeContract;
    private final Payload payload;
    private final Map<String, RuleAttributeBinding> bindings;

    RuleResult(RulePurposeContract purposeContract, Payload payload, Map<String, RuleAttributeBinding> bindings) {
        this.purposeContract = purposeContract;
        this.payload = payload;
        this.bindings = Map.copyOf(bindings);
    }

    public RulePurposeContract purposeContract() { return purposeContract; }
    public Type type() { return purposeContract.resultType(); }
    public Payload payload() { return payload; }

    /** 仅包含结果 SORT 引用属性；condition+sort 并集由后续 publication adapter 负责。 */
    public Map<String, RuleAttributeBinding> bindings() { return bindings; }
}
