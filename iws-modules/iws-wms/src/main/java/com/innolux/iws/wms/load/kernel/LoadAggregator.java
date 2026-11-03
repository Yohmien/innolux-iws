/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 按有效物料叶去重汇合属性并严格分组实存数量；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 纯汇合入口。每个有效物料叶必须且只能提供一份事实；载具自身不贡献物料量。
 * 不同单位或规则版本绝不合并；未知单位或版本拒绝汇总，null 料号保持独立桶。
 */
public final class LoadAggregator {
    private LoadAggregator() {
    }

    public static LoadAggregate aggregate(
        LoadTree tree,
        List<LoadLeafData> sourceLeaves,
        List<LoadCarrierProperty> sourceCarrierProperties
    ) {
        if (tree == null || sourceLeaves == null || sourceCarrierProperties == null) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_INPUT_NULL);
        }

        Set<Long> expectedLeafIds = new HashSet<>(tree.materialLeafIds());
        Set<Long> seenLeafIds = new HashSet<>();
        NavigableMap<String, LoadValue.Kind> materialKinds = new TreeMap<>(LoadValue::compareText);
        NavigableMap<Long, NavigableMap<String, AttributeAccumulator>> material =
            new TreeMap<>();
        NavigableMap<QuantityKey, QuantityAccumulator> quantities = new TreeMap<>(
            QuantityKey.COMPARATOR);

        for (LoadLeafData leaf : sourceLeaves) {
            if (leaf == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_LEAF_INPUT_NULL);
            }
            if (!expectedLeafIds.contains(leaf.nodeId())) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_LEAF_UNKNOWN);
            }
            if (!seenLeafIds.add(leaf.nodeId())) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_LEAF_DUPLICATE);
            }

            long rootId = tree.rootIdOf(leaf.nodeId());
            for (LoadAttributeFact fact : leaf.attributes()) {
                for (LoadValue value : fact.values()) {
                    LoadValue.Kind previous = materialKinds.putIfAbsent(fact.name(), value.kind());
                    if (previous != null && previous != value.kind()) {
                        throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_TYPE_CONFLICT);
                    }
                }
                material.computeIfAbsent(rootId, ignored -> new TreeMap<>(LoadValue::compareText))
                    .computeIfAbsent(fact.name(), ignored -> new AttributeAccumulator())
                    .add(fact, leaf.nodeId());
            }
            if (leaf.quantity().unit() == null || leaf.quantity().unit().isBlank()
                || leaf.quantity().rulePackageVersion() == null
                || leaf.quantity().rulePackageVersion().isBlank()) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_QUANTITY_GROUP_UNKNOWN);
            }
            QuantityKey key = new QuantityKey(leaf.quantity().materialCode(),
                leaf.quantity().unit(), leaf.quantity().rulePackageVersion());
            quantities.computeIfAbsent(key, ignored -> new QuantityAccumulator())
                .add(leaf.quantity(), leaf.nodeId());
        }
        if (seenLeafIds.size() != expectedLeafIds.size()) {
            throw LoadKernelException.failure(
                LoadKernelException.Code.LOAD_LEAF_INPUT_MISSING);
        }

        material.forEach((rootId, attributes) -> attributes.values().forEach(attribute ->
            attribute.completeAbsent(tree.materialLeafIdsByRoot().get(rootId))));
        NavigableMap<Long, NavigableMap<String, LoadAggregate.PropertySummary>> summaries =
            summarize(material);
        NavigableMap<Long, NavigableMap<String, AttributeAccumulator>> carrier =
            collectCarrierProperties(tree, sourceCarrierProperties);
        NavigableMap<Long, NavigableMap<String, LoadAggregate.PropertySummary>> carrierSummaries =
            summarize(carrier);
        List<LoadAggregate.QuantityGroup> quantityGroups = quantities.entrySet().stream()
            .map(entry -> entry.getValue().toGroup(entry.getKey()))
            .toList();
        return new LoadAggregate(summaries, carrierSummaries, quantityGroups,
            tree.topLevelSlotCount());
    }

    private static NavigableMap<Long, NavigableMap<String, AttributeAccumulator>>
    collectCarrierProperties(LoadTree tree, List<LoadCarrierProperty> source) {
        NavigableMap<Long, NavigableMap<String, AttributeAccumulator>> properties =
            new TreeMap<>();
        for (LoadCarrierProperty carrierProperty : source) {
            if (carrierProperty == null) {
                throw LoadKernelException.failure(
                    LoadKernelException.Code.LOAD_CARRIER_ATTRIBUTE_INVALID);
            }
            LoadNode node = tree.node(carrierProperty.carrierNodeId()).orElse(null);
            if (node == null || node.type() != LoadNode.Type.CARRIER) {
                throw LoadKernelException.failure(
                    LoadKernelException.Code.LOAD_CARRIER_ATTRIBUTE_INVALID);
            }
            NavigableMap<String, AttributeAccumulator> attributes =
                properties.computeIfAbsent(node.id(), ignored -> new TreeMap<>(LoadValue::compareText));
            String name = carrierProperty.property().name();
            if (attributes.putIfAbsent(name, new AttributeAccumulator()) != null) {
                throw LoadKernelException.failure(
                    LoadKernelException.Code.LOAD_ATTRIBUTE_DUPLICATE);
            }
            attributes.get(name).add(carrierProperty.property(), node.id());
        }
        return properties;
    }

    private static NavigableMap<Long, NavigableMap<String, LoadAggregate.PropertySummary>>
    summarize(NavigableMap<Long, NavigableMap<String, AttributeAccumulator>> source) {
        NavigableMap<Long, NavigableMap<String, LoadAggregate.PropertySummary>> result =
            new TreeMap<>();
        source.forEach((id, attributes) -> {
            NavigableMap<String, LoadAggregate.PropertySummary> summaries = new TreeMap<>(LoadValue::compareText);
            attributes.forEach((name, accumulator) ->
                summaries.put(name, accumulator.summary()));
            result.put(id, summaries);
        });
        return result;
    }

    private static final class AttributeAccumulator {
        private final TreeSet<Long> absentRefs = new TreeSet<>();
        private final TreeSet<Long> nullRefs = new TreeSet<>();
        private final NavigableMap<LoadValue, TreeSet<Long>> values = new TreeMap<>();
        private final TreeSet<Long> providedRefs = new TreeSet<>();
        private LoadValue.Kind kind;

        void add(LoadAttributeFact fact, long leafRef) {
            providedRefs.add(leafRef);
            for (LoadValue value : fact.values()) {
                if (kind != null && kind != value.kind()) {
                    throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_TYPE_CONFLICT);
                }
                kind = value.kind();
            }
            switch (fact.state()) {
                case ABSENT -> absentRefs.add(leafRef);
                case NULL -> nullRefs.add(leafRef);
                case PRESENT -> fact.values().forEach(value ->
                    values.computeIfAbsent(value, ignored -> new TreeSet<>()).add(leafRef));
            }
        }

        void completeAbsent(List<Long> rootLeafIds) {
            for (Long leafId : rootLeafIds) {
                if (!providedRefs.contains(leafId)) {
                    absentRefs.add(leafId);
                }
            }
        }

        LoadAggregate.PropertySummary summary() {
            List<LoadAggregate.ValueOccurrence> occurrences = new ArrayList<>();
            values.forEach((value, refs) ->
                occurrences.add(new LoadAggregate.ValueOccurrence(value, List.copyOf(refs))));
            return new LoadAggregate.PropertySummary(List.copyOf(absentRefs),
                List.copyOf(nullRefs), occurrences);
        }
    }

    private static final class QuantityAccumulator {
        private BigDecimal amount = BigDecimal.ZERO;
        private final TreeSet<Long> leafRefs = new TreeSet<>();

        void add(LoadQuantity quantity, long leafRef) {
            // 汇总仅为展示投影，精确求和，不沿用单叶存储上限或回写实存。
            amount = amount.add(quantity.amount());
            leafRefs.add(leafRef);
        }

        LoadAggregate.QuantityGroup toGroup(QuantityKey key) {
            return new LoadAggregate.QuantityGroup(key.materialCode(), key.unit(),
                key.rulePackageVersion(), amount, List.copyOf(leafRefs));
        }
    }

    private record QuantityKey(String materialCode, String unit, String rulePackageVersion) {
        private static final Comparator<QuantityKey> COMPARATOR =
            Comparator.comparing(QuantityKey::materialCode,
                    Comparator.nullsFirst(LoadValue::compareText))
                .thenComparing(QuantityKey::unit,
                    Comparator.nullsFirst(LoadValue::compareText))
                .thenComparing(QuantityKey::rulePackageVersion,
                    Comparator.nullsFirst(LoadValue::compareText));
    }
}
