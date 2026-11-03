/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核测试
 * @Version      : V1.0
 * @Description  : 验证叶去重、三态属性、每值引用、独立命名空间和严格数量分组；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("local")
class LoadAggregatorTest {
    @Test
    void aggregatesByDedupedLeafAndKeepsAbsentNullSingleAndConflictSets() {
        LoadTree tree = fixtureTree();
        LoadAggregate aggregate = LoadAggregator.aggregate(tree, List.of(
            leaf(50, List.of(factAbsent("missing"), factNull("memo"),
                fact("lot", LoadValue.text("A"))), quantity("0", null, "KG", "v1")),
            leaf(20, List.of(factAbsent("missing"), factNull("memo"),
                fact("lot", LoadValue.text("A"))), quantity("20", null, "KG", "v1")),
            leaf(30, List.of(factAbsent("missing"), factNull("memo"),
                fact("lot", LoadValue.text("B"))), quantity("6.000", null, "KG", "v1")),
            leaf(60, List.of(fact("flag", LoadValue.bool(true))),
                quantity("1", null, "KG", "v2")),
            leaf(70, List.of(), quantity("0", "M-9", "BOX", "v1"))
        ), List.of(
            new LoadCarrierProperty(10, fact("memo", LoadValue.text("carrier-only"))),
            new LoadCarrierProperty(40, fact("kind", LoadValue.text("TRAY")))
        ));

        LoadAggregate.PropertySummary carrierLot =
            aggregate.materialAttributesByRoot().get(10L).get("lot");
        assertThat(carrierLot.cardinality()).isEqualTo(LoadAggregate.Cardinality.CONFLICT_SET);
        assertThat(carrierLot.values()).hasSize(2);
        assertThat(carrierLot.values().get(0).value()).isEqualTo(LoadValue.text("A"));
        assertThat(carrierLot.values().get(0).leafRefs()).containsExactly(20L);
        assertThat(carrierLot.values().get(1).value()).isEqualTo(LoadValue.text("B"));
        assertThat(carrierLot.values().get(1).leafRefs()).containsExactly(30L);

        LoadAggregate.PropertySummary rootMaterialLot =
            aggregate.materialAttributesByRoot().get(50L).get("lot");
        assertThat(rootMaterialLot.cardinality())
            .isEqualTo(LoadAggregate.Cardinality.SINGLE_VALUE);
        assertThat(rootMaterialLot.values().getFirst().leafRefs()).containsExactly(50L);

        assertThat(aggregate.materialAttributesByRoot().get(10L).get("memo").absentLeafRefs())
            .isEmpty();
        assertThat(aggregate.materialAttributesByRoot().get(10L).get("memo").nullLeafRefs())
            .containsExactly(20L, 30L);
        assertThat(aggregate.materialAttributesByRoot().get(10L).get("missing").absentLeafRefs())
            .containsExactly(20L, 30L);
        assertThat(aggregate.materialAttributesByRoot().get(10L).get("missing").cardinality())
            .isEqualTo(LoadAggregate.Cardinality.NO_VALUE);
    }

    @Test
    void keepsCarrierOwnPropertiesInSeparateNamespaceAndCountsOnlyRoots() {
        LoadAggregate aggregate = LoadAggregator.aggregate(
            fixtureTree(),
            fixtureLeaves(),
            List.of(new LoadCarrierProperty(10,
                fact("memo", LoadValue.text("carrier-only"))))
        );

        assertThat(aggregate.materialAttributesByRoot().get(10L).get("memo").nullLeafRefs())
            .containsExactly(20L, 30L);
        assertThat(aggregate.carrierOwnAttributes().get(10L).get("memo")
            .values().getFirst().value()).isEqualTo(LoadValue.text("carrier-only"));
        assertThat(aggregate.topLevelSlotCount()).isEqualTo(4);
    }

    @Test
    void quantityGroupsUseNullableCodeUnitAndVersionWithoutDefaultsOrCrossGroupMerge() {
        LoadAggregate aggregate = LoadAggregator.aggregate(
            fixtureTree(), fixtureLeaves(), List.of());

        assertThat(aggregate.quantityGroups()).hasSize(3);
        LoadAggregate.QuantityGroup first = aggregate.quantityGroups().get(0);
        assertThat(first.materialCode()).isNull();
        assertThat(first.unit()).isEqualTo("KG");
        assertThat(first.rulePackageVersion()).isEqualTo("v1");
        assertThat(first.amount()).isEqualByComparingTo("26.000");
        assertThat(first.leafRefs()).containsExactly(20L, 30L, 50L);

        LoadAggregate.QuantityGroup second = aggregate.quantityGroups().get(1);
        assertThat(second.rulePackageVersion()).isEqualTo("v2");
        assertThat(second.amount()).isEqualByComparingTo("1");
        assertThat(second.leafRefs()).containsExactly(60L);

        LoadAggregate.QuantityGroup third = aggregate.quantityGroups().get(2);
        assertThat(third.materialCode()).isEqualTo("M-9");
        assertThat(third.unit()).isEqualTo("BOX");
        assertThat(third.rulePackageVersion()).isEqualTo("v1");
        assertThat(third.amount()).isZero();
        assertThat(third.leafRefs()).containsExactly(70L);
    }

    @Test
    void allSupportedScalarTypesRemainThemselves() {
        List<LoadValue> values = List.of(LoadValue.text("01"), LoadValue.decimal(new BigDecimal("1.20")),
                LoadValue.integer(7L), LoadValue.bool(false),
                LoadValue.date(LocalDate.of(2026, 10, 29)),
                LoadValue.instant(Instant.parse("2026-10-29T00:00:00Z")));
        for (LoadValue value : values) {
            assertThat(fact("typed", value).values()).containsExactly(value);
        }

        assertThat(values).extracting(LoadValue::kind).containsExactlyInAnyOrder(
            LoadValue.Kind.TEXT, LoadValue.Kind.DECIMAL, LoadValue.Kind.INTEGER,
            LoadValue.Kind.BOOLEAN, LoadValue.Kind.DATE, LoadValue.Kind.INSTANT);
    }

    @Test
    void rejectsDuplicateMissingAndUnknownLeafFacts() {
        LoadLeafData leaf20 = fixtureLeaves().getFirst();
        assertCode(() -> LoadAggregator.aggregate(
            fixtureTree(), List.of(leaf20, leaf20), List.of()),
            LoadKernelException.Code.LOAD_LEAF_DUPLICATE);
        assertCode(() -> LoadAggregator.aggregate(
            fixtureTree(), fixtureLeaves().subList(0, 3), List.of()),
            LoadKernelException.Code.LOAD_LEAF_INPUT_MISSING);
        assertCode(() -> LoadAggregator.aggregate(
            fixtureTree(),
            List.of(leaf20, new LoadLeafData(10, List.of(),
                new LoadQuantity(BigDecimal.ZERO, null, null, null))),
            List.of()), LoadKernelException.Code.LOAD_LEAF_UNKNOWN);
    }

    @Test
    void rejectsInvalidAttributeStatesAndQuantityPrecisionWithoutRounding() {
        assertCode(() -> new LoadAttributeFact("x", LoadAttributeFact.State.ABSENT,
            List.of(LoadValue.text("unexpected"))),
            LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
        assertCode(() -> new LoadAttributeFact("x", LoadAttributeFact.State.NULL,
            List.of(LoadValue.text("unexpected"))),
            LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
        assertCode(() -> new LoadAttributeFact("x", LoadAttributeFact.State.PRESENT, List.of()),
            LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
        assertCode(() -> new LoadQuantity(new BigDecimal("-1"), null, "KG", "v1"),
            LoadKernelException.Code.LOAD_QUANTITY_INVALID);
        assertCode(() -> new LoadQuantity(new BigDecimal("0.0000001"), null, "KG", "v1"),
            LoadKernelException.Code.LOAD_QUANTITY_INVALID);
        assertCode(() -> new LoadQuantity(new BigDecimal("100000000000000.0"), null, "KG", "v1"),
            LoadKernelException.Code.LOAD_QUANTITY_INVALID);
    }

    @Test
    void rejectsDuplicateLeafAttributeAndCarrierPropertyNames() {
        assertCode(() -> new LoadLeafData(20,
            List.of(factAbsent("same"), factNull("same")),
            new LoadQuantity(BigDecimal.ZERO, null, null, null)),
            LoadKernelException.Code.LOAD_ATTRIBUTE_DUPLICATE);
        assertCode(() -> LoadAggregator.aggregate(
            fixtureTree(),
            fixtureLeaves(),
            List.of(
                new LoadCarrierProperty(10, fact("same", LoadValue.text("a"))),
                new LoadCarrierProperty(10, fact("same", LoadValue.text("b"))))),
            LoadKernelException.Code.LOAD_ATTRIBUTE_DUPLICATE);
    }

    @Test
    void aggregateAndSourceCollectionsAreImmutableSnapshots() {
        List<LoadAttributeFact> attributes = new ArrayList<>(List.of(
            fact("lot", LoadValue.text("A"))));
        List<LoadLeafData> leaves = new ArrayList<>(List.of(
            new LoadLeafData(20, attributes, new LoadQuantity(
                BigDecimal.ZERO, null, "KG", "v1")),
            leaf(30, List.of(fact("lot", LoadValue.text("B"))),
                quantity("0", null, "KG", "v1")),
            leaf(50, List.of(), quantity("0", null, "KG", "v1")),
            leaf(60, List.of(), quantity("0", null, "KG", "v2")),
            leaf(70, List.of(), quantity("0", "M-9", "BOX", "v1"))
        ));
        LoadAggregate aggregate = LoadAggregator.aggregate(fixtureTree(), leaves, List.of());

        attributes.clear();
        leaves.clear();

        assertThat(aggregate.materialAttributesByRoot().get(10L).get("lot")
            .values().getFirst().leafRefs()).containsExactly(20L);
        assertThat(aggregate.quantityGroups()).hasSize(3);
        assertThatThrownBy(() -> aggregate.carrierOwnAttributes().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> aggregate.quantityGroups().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void omittedFactsBecomeAbsentOnlyWithinTheirOwnRoot() {
        LoadTree tree = LoadTreeKernel.parse(
            List.of(carrier(1), material(2), material(3), carrier(4), material(5), material(6)),
            List.of(new LoadEdge(2, 1), new LoadEdge(3, 1), new LoadEdge(5, 4), new LoadEdge(6, 4)));
        LoadAggregate result = LoadAggregator.aggregate(tree, List.of(
            leaf(2, List.of(fact("color", LoadValue.text("RED"))), quantity("1", null, "KG", "v1")),
            leaf(3, List.of(), quantity("1", null, "KG", "v1")),
            leaf(5, List.of(factNull("color"), fact("other", LoadValue.text("X"))), quantity("1", null, "KG", "v1")),
            leaf(6, List.of(), quantity("1", null, "KG", "v1"))), List.of());
        var red = result.materialAttributesByRoot().get(1L).get("color");
        assertThat(red.absentLeafRefs()).containsExactly(3L);
        assertThat(red.values().getFirst().leafRefs()).containsExactly(2L);
        assertThat(result.materialAttributesByRoot().get(1L)).doesNotContainKey("other");
        var nullColor = result.materialAttributesByRoot().get(4L).get("color");
        assertThat(nullColor.nullLeafRefs()).containsExactly(5L);
        assertThat(nullColor.absentLeafRefs()).containsExactly(6L);
        assertThat(nullColor.values()).isEmpty();
    }

    @Test
    void rejectsUnknownQuantityDimensionsEvenForSingleZeroLeaf() {
        LoadTree tree = LoadTreeKernel.parse(List.of(material(1)), List.of());
        for (String unknown : java.util.Arrays.asList(null, "", " ", "\t")) {
            LoadQuantity unknownUnit = quantity("0", null, unknown, "v1");
            LoadQuantity unknownVersion = quantity("0", null, "KG", unknown);
            assertCode(() -> LoadAggregator.aggregate(tree,
                List.of(leaf(1, List.of(), unknownUnit)), List.of()),
                LoadKernelException.Code.LOAD_QUANTITY_GROUP_UNKNOWN);
            assertCode(() -> LoadAggregator.aggregate(tree,
                List.of(leaf(1, List.of(), unknownVersion)), List.of()),
                LoadKernelException.Code.LOAD_QUANTITY_GROUP_UNKNOWN);
        }
        assertThat(tree.rootIds()).containsExactly(1L);
    }

    @Test
    void decimalRepresentativeAndRefsDoNotDependOnLeafOrder() {
        List<LoadLeafData> leaves = List.of(
            leaf(2, List.of(fact("n", LoadValue.decimal(new BigDecimal("1.0")))), quantity("1", null, "KG", "v1")),
            leaf(3, List.of(fact("n", LoadValue.decimal(new BigDecimal("1.00")))), quantity("1", null, "KG", "v1")));
        var forward = LoadAggregator.aggregate(pairTree(), leaves, List.of());
        var reverse = LoadAggregator.aggregate(pairTree(), List.of(leaves.get(1), leaves.get(0)), List.of());
        assertThat(forward.materialAttributesByRoot()).isEqualTo(reverse.materialAttributesByRoot());
        var n = forward.materialAttributesByRoot().get(1L).get("n");
        assertThat(n.values()).hasSize(1);
        assertThat(n.values().getFirst().value()).isEqualTo(LoadValue.decimal(BigDecimal.ONE));
        assertThat(((LoadValue.NumericValue) n.values().getFirst().value()).value().scale()).isZero();
        assertThat(n.values().getFirst().leafRefs()).containsExactly(2L, 3L);
    }

    @Test
    void stringsAndAllQuantityKeyDimensionsUseCodePointOrder() {
        String bmp = "\uE000";
        String supplementary = "\uD800\uDC00";
        assertThat(supplementary.compareTo(bmp)).isNegative();
        var texts = new LoadAttributeFact("text", LoadAttributeFact.State.PRESENT,
            List.of(LoadValue.text(supplementary), LoadValue.text(bmp)));
        assertThat(texts.values()).containsExactly(LoadValue.text(bmp), LoadValue.text(supplementary));
        for (int dimension = 0; dimension < 3; dimension++) {
            String[] a = {"M", "KG", "v1"};
            String[] b = a.clone();
            a[dimension] = bmp;
            b[dimension] = supplementary;
            var result = LoadAggregator.aggregate(pairTree(), List.of(
                leaf(2, List.of(), quantity("1", b[0], b[1], b[2])),
                leaf(3, List.of(), quantity("1", a[0], a[1], a[2]))), List.of());
            assertThat(result.quantityGroups()).extracting(LoadAggregate.QuantityGroup::leafRefs)
                .containsExactly(List.of(3L), List.of(2L));
        }
    }

    @Test
    void rejectsMixedKindsInsideOneFactAndAcrossLeaves() {
        assertCode(() -> new LoadAttributeFact("color", LoadAttributeFact.State.PRESENT,
            List.of(LoadValue.text("1"), LoadValue.integer(1L))),
            LoadKernelException.Code.LOAD_ATTRIBUTE_TYPE_CONFLICT);
        assertCode(() -> LoadAggregator.aggregate(pairTree(), List.of(
            leaf(2, List.of(fact("color", LoadValue.text("1"))), quantity("1", null, "KG", "v1")),
            leaf(3, List.of(fact("color", LoadValue.integer(1L))), quantity("1", null, "KG", "v1"))), List.of()),
            LoadKernelException.Code.LOAD_ATTRIBUTE_TYPE_CONFLICT);
        assertCode(() -> LoadValue.text("1").compareTo(LoadValue.integer(1L)),
            LoadKernelException.Code.LOAD_ATTRIBUTE_TYPE_CONFLICT);
    }

    @Test
    void sumsTwoMaximumLegalLeavesExactlyBeyondSingleLeafStorageLimit() {
        BigDecimal maximum = new BigDecimal("99999999999999.999999");
        LoadQuantity first = new LoadQuantity(maximum, null, "KG", "v1");
        LoadQuantity second = new LoadQuantity(maximum, null, "KG", "v1");
        var result = LoadAggregator.aggregate(pairTree(), List.of(
            leaf(2, List.of(), first), leaf(3, List.of(), second)), List.of());
        assertThat(result.quantityGroups()).hasSize(1);
        assertThat(result.quantityGroups().getFirst().amount())
            .isEqualTo(new BigDecimal("199999999999999.999998"));
        assertThat(result.quantityGroups().getFirst().leafRefs()).containsExactly(2L, 3L);
        assertThat(first.amount()).isEqualTo(maximum);
        assertThat(second.amount()).isEqualTo(maximum);
    }

    private static LoadTree pairTree() {
        return LoadTreeKernel.parse(List.of(carrier(1), material(2), material(3)),
            List.of(new LoadEdge(2, 1), new LoadEdge(3, 1)));
    }

    private static LoadTree fixtureTree() {
        return LoadTreeKernel.parse(
            List.of(carrier(10), material(20), material(30), carrier(40),
                material(50), material(60), material(70)),
            List.of(new LoadEdge(20, 10), new LoadEdge(30, 10),
                new LoadEdge(40, 10)));
    }

    private static List<LoadLeafData> fixtureLeaves() {
        return List.of(
            leaf(20, List.of(factNull("memo"), fact("lot", LoadValue.text("A"))),
                quantity("20", null, "KG", "v1")),
            leaf(30, List.of(factNull("memo"), fact("lot", LoadValue.text("B"))),
                quantity("6.000", null, "KG", "v1")),
            leaf(50, List.of(factNull("memo"), fact("lot", LoadValue.text("A"))),
                quantity("0", null, "KG", "v1")),
            leaf(60, List.of(), quantity("1", null, "KG", "v2")),
            leaf(70, List.of(), quantity("0", "M-9", "BOX", "v1"))
        );
    }

    private static LoadLeafData leaf(long id, List<LoadAttributeFact> attributes,
                                     LoadQuantity quantity) {
        return new LoadLeafData(id, attributes, quantity);
    }

    private static LoadQuantity quantity(String amount, String materialCode,
                                         String unit, String version) {
        return new LoadQuantity(new BigDecimal(amount), materialCode, unit, version);
    }

    private static LoadAttributeFact fact(String name, LoadValue value) {
        return new LoadAttributeFact(name, LoadAttributeFact.State.PRESENT, List.of(value));
    }

    private static LoadAttributeFact factNull(String name) {
        return new LoadAttributeFact(name, LoadAttributeFact.State.NULL, List.of());
    }

    private static LoadAttributeFact factAbsent(String name) {
        return new LoadAttributeFact(name, LoadAttributeFact.State.ABSENT, List.of());
    }

    private static LoadNode material(long id) {
        return new LoadNode(id, LoadNode.Type.MATERIAL, id, null);
    }

    private static LoadNode carrier(long id) {
        return new LoadNode(id, LoadNode.Type.CARRIER, null, id);
    }

    private static void assertCode(Runnable action, LoadKernelException.Code code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(LoadKernelException.class,
                exception -> assertThat(exception.code()).isEqualTo(code.name()));
    }
}
