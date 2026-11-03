/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 保存载具物料属性、载具自有属性和严格分组数量的只读汇合结果；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** 汇合结果的结构化快照；不携带持久化、锁或容量分配结论。 */
public final class LoadAggregate {
    private final NavigableMap<Long, NavigableMap<String, PropertySummary>> materialAttributesByRoot;
    private final NavigableMap<Long, NavigableMap<String, PropertySummary>> carrierOwnAttributes;
    private final List<QuantityGroup> quantityGroups;
    private final int topLevelSlotCount;

    LoadAggregate(
        NavigableMap<Long, NavigableMap<String, PropertySummary>> materialAttributesByRoot,
        NavigableMap<Long, NavigableMap<String, PropertySummary>> carrierOwnAttributes,
        List<QuantityGroup> quantityGroups,
        int topLevelSlotCount
    ) {
        this.materialAttributesByRoot = freezeNestedMap(materialAttributesByRoot);
        this.carrierOwnAttributes = freezeNestedMap(carrierOwnAttributes);
        this.quantityGroups = List.copyOf(quantityGroups);
        this.topLevelSlotCount = topLevelSlotCount;
    }

    private static NavigableMap<Long, NavigableMap<String, PropertySummary>> freezeNestedMap(
        NavigableMap<Long, NavigableMap<String, PropertySummary>> source
    ) {
        NavigableMap<Long, NavigableMap<String, PropertySummary>> copy = new TreeMap<>();
        source.forEach((id, attributes) -> copy.put(id,
            Collections.unmodifiableNavigableMap(new TreeMap<>(attributes))));
        return Collections.unmodifiableNavigableMap(copy);
    }

    public NavigableMap<Long, NavigableMap<String, PropertySummary>> materialAttributesByRoot() {
        return materialAttributesByRoot;
    }

    public NavigableMap<Long, NavigableMap<String, PropertySummary>> carrierOwnAttributes() {
        return carrierOwnAttributes;
    }

    public List<QuantityGroup> quantityGroups() {
        return quantityGroups;
    }

    public int topLevelSlotCount() {
        return topLevelSlotCount;
    }

    public enum Cardinality {
        NO_VALUE,
        SINGLE_VALUE,
        CONFLICT_SET
    }

    /** 一个不同属性值及其排序后的叶节点引用。 */
    public record ValueOccurrence(LoadValue value, List<Long> leafRefs) {
        public ValueOccurrence {
            if (value == null || leafRefs == null) {
                throw LoadKernelException.failure(
                    LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
            leafRefs = List.copyOf(leafRefs);
        }
    }

    /** 同时保留缺失引用、显式 null 引用以及不同值的完整冲突集合。 */
    public record PropertySummary(List<Long> absentLeafRefs, List<Long> nullLeafRefs,
                                  List<ValueOccurrence> values) {
        public PropertySummary {
            if (absentLeafRefs == null || nullLeafRefs == null || values == null
                || values.stream().anyMatch(value -> value == null)) {
                throw LoadKernelException.failure(
                    LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
            absentLeafRefs = List.copyOf(absentLeafRefs);
            nullLeafRefs = List.copyOf(nullLeafRefs);
            values = List.copyOf(values);
        }

        public Cardinality cardinality() {
            return switch (values.size()) {
                case 0 -> Cardinality.NO_VALUE;
                case 1 -> Cardinality.SINGLE_VALUE;
                default -> Cardinality.CONFLICT_SET;
            };
        }
    }

    /** 数量只按 materialCode、unit、rulePackageVersion 的精确三元组分组。 */
    public record QuantityGroup(String materialCode, String unit, String rulePackageVersion,
                                BigDecimal amount, List<Long> leafRefs) {
        public QuantityGroup {
            if (amount == null || leafRefs == null) {
                throw LoadKernelException.failure(
                    LoadKernelException.Code.LOAD_QUANTITY_INVALID);
            }
            leafRefs = List.copyOf(leafRefs);
        }
    }
}
