/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 验证硬筛选、稳定排序、批量影子账和只读隔离；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class AllocationKernelTest {
    private static final String PCS = "PCS";

    @Test
    void shouldRejectAllHardFilterReasons() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).enabled(false).capacity("QTY", "10", "0", "0", "0", "0").build(),
            storage(2).safe(false).capacity("QTY", "10", "0", "0", "0", "0").build(),
            storage(3).forbidden(true).capacity("QTY", "10", "0", "0", "0", "0").build(),
            storage(4).reachable(false).capacity("QTY", "10", "0", "0", "0", "0").build(),
            storage(5).accessOrder(null).capacity("QTY", "10", "0", "0", "0", "0").build()
        );

        AllocationPreview result = AllocationKernel.preview(storage, capacityRequest(99, "QTY", "1"));

        assertEquals(AllocationPreview.Outcome.NO_CANDIDATE, result.outcome());
        assertRejected(result, 1, AllocationPreview.Rejection.Code.STORAGE_DISABLED);
        assertRejected(result, 2, AllocationPreview.Rejection.Code.SAFETY_BLOCKED);
        assertRejected(result, 3, AllocationPreview.Rejection.Code.FORBIDDEN);
        assertRejected(result, 4, AllocationPreview.Rejection.Code.PATH_UNREACHABLE);
        assertRejected(result, 5, AllocationPreview.Rejection.Code.ACCESS_ORDER_MISSING);
    }

    @Test
    void shouldPreferCompatibleMaterialBeforeBestFit() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("QTY", "109", "0", "0", "0", "0").build(),
            storage(2).compatibleMaterial(true)
                .capacity("QTY", "100", "0", "0", "0", "0").build(),
            storage(3).compatibleLogicalScope(true)
                .capacity("QTY", "108", "0", "0", "0", "0").build()
        );

        AllocationPreview result = AllocationKernel.preview(storage, capacityRequest(7, "QTY", "10"));

        assertEquals(List.of(2L, 3L, 1L),
            result.candidates().stream().map(AllocationPreview.Candidate::storageId).toList());
        assertEquals(2L, result.selectedStorageId());
    }

    @Test
    void shouldApplyProtectionCostBeforeBestFit() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).protection(new AllocationProtection(1, "唯一深位通道"))
                .capacity("QTY", "100", "0", "0", "0", "0").build(),
            storage(2).capacity("QTY", "95", "0", "0", "0", "0").build()
        );

        AllocationPreview result = AllocationKernel.preview(storage, capacityRequest(8, "QTY", "10"));

        assertEquals(2L, result.selectedStorageId());
        assertEquals("唯一深位通道", result.candidates().get(1).protectionReason());
    }

    @Test
    void shouldRejectAllocationThatConsumesProtectedResidual() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("QTY", "10", "0", "0", "0", "6").build()
        );

        AllocationPreview result = AllocationKernel.preview(storage, capacityRequest(9, "QTY", "5"));

        assertEquals(AllocationPreview.Outcome.NO_CANDIDATE, result.outcome());
        assertRejected(result, 1, AllocationPreview.Rejection.Code.PROTECTED_RESERVE);
    }

    @Test
    void shouldCompareMultiDimensionsInFrozenOrderWithoutWeighting() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("LENGTH", "10", "0", "0", "0", "0")
                .capacity("WIDTH", "100", "0", "0", "0", "0").build(),
            storage(2).capacity("LENGTH", "11", "0", "0", "0", "0")
                .capacity("WIDTH", "2", "0", "0", "0", "0").build()
        );
        Map<String, AllocationQuantity> quantities = new LinkedHashMap<>();
        quantities.put("LENGTH", quantity("1"));
        quantities.put("WIDTH", quantity("1"));

        AllocationPreview lengthFirst = AllocationKernel.preview(
            storage,
            new AllocationRequest(
                new AllocationDemand(10, quantities, 0),
                new AllocationPolicy(List.of("LENGTH", "WIDTH"))));
        AllocationPreview widthFirst = AllocationKernel.preview(
            storage,
            new AllocationRequest(
                new AllocationDemand(10, quantities, 0),
                new AllocationPolicy(List.of("WIDTH", "LENGTH"))));

        assertEquals(1L, lengthFirst.selectedStorageId());
        assertEquals(2L, widthFirst.selectedStorageId());
    }

    @Test
    void shouldNotUseAddressTextOrAccessOrderForFinalTieBreak() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).address("ZZZ").accessOrder(9)
                .capacity("QTY", "10", "0", "0", "0", "0").build(),
            storage(2).address("AAA").accessOrder(1)
                .capacity("QTY", "10", "0", "0", "0", "0").build()
        );

        AllocationPreview result = AllocationKernel.preview(storage, capacityRequest(11, "QTY", "1"));

        assertEquals(1L, result.selectedStorageId());
        assertEquals(9, result.candidates().getFirst().accessOrder());
    }

    @Test
    void shouldProduceSameRankingForSameFrozenSnapshot() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("QTY", "20", "0", "0", "0", "0").build(),
            storage(2).capacity("QTY", "10", "0", "0", "0", "0").build(),
            storage(3).capacity("QTY", "30", "0", "0", "0", "0").build()
        );

        AllocationPreview forward = AllocationKernel.preview(storage, capacityRequest(12, "QTY", "1"));
        AllocationPreview reverse = AllocationKernel.preview(
            List.of(storage.get(2), storage.get(1), storage.get(0)),
            capacityRequest(12, "QTY", "1"));

        assertEquals(List.of(2L, 1L, 3L), forward.candidates().stream()
            .map(AllocationPreview.Candidate::storageId).toList());
        assertEquals(forward, reverse);
    }

    @Test
    void shouldRejectDuplicateStorageAndDuplicateBatchUnit() {
        AllocationStorageSnapshot duplicate =
            storage(1).capacity("QTY", "10", "0", "0", "0", "0").build();
        AllocationKernelException storageError = assertThrows(
            AllocationKernelException.class,
            () -> AllocationKernel.preview(
                List.of(duplicate, duplicate), capacityRequest(13, "QTY", "1")));
        assertEquals(AllocationKernelException.Code.ALLOCATION_STORAGE_DUPLICATE.name(),
            storageError.code());

        AllocationKernelException unitError = assertThrows(
            AllocationKernelException.class,
            () -> AllocationKernel.previewBatch(
                List.of(duplicate),
                List.of(capacityRequest(14, "QTY", "1"),
                    capacityRequest(14, "QTY", "2"))));
        assertEquals(AllocationKernelException.Code.ALLOCATION_DEMAND_DUPLICATE.name(),
            unitError.code());
    }

    @Test
    void shouldExplainCapacityUnitAndPrecisionMismatch() {
        AllocationStorageSnapshot unitStorage =
            storage(1).capacity("LENGTH", "CM", "10", "0").build();
        AllocationStorageSnapshot precisionStorage =
            storage(2).capacity("LENGTH", "CM", "1.00", "0", "0", "0", "0", 2).build();

        AllocationPreview unitResult = AllocationKernel.preview(
            List.of(unitStorage),
            new AllocationRequest(
                new AllocationDemand(15,
                    Map.of("LENGTH", new AllocationQuantity(new BigDecimal("1"), "MM", 0)), 0),
                new AllocationPolicy(List.of("LENGTH"))));
        AllocationPreview precisionResult = AllocationKernel.preview(
            List.of(precisionStorage),
            new AllocationRequest(
                new AllocationDemand(16,
                    Map.of("LENGTH", new AllocationQuantity(new BigDecimal("1"), "CM", 0)), 0),
                new AllocationPolicy(List.of("LENGTH"))));

        assertRejected(unitResult, 1, AllocationPreview.Rejection.Code.UNIT_MISMATCH);
        assertRejected(precisionResult, 2, AllocationPreview.Rejection.Code.PRECISION_MISMATCH);
    }

    @Test
    void shouldRejectMissingCapacityAndInsufficientResidual() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).build(),
            storage(2).capacity("QTY", "5", "0", "0", "0", "0").build()
        );

        AllocationPreview result = AllocationKernel.preview(storage, capacityRequest(17, "QTY", "6"));

        assertRejected(result, 1, AllocationPreview.Rejection.Code.CAPACITY_MISSING);
        assertRejected(result, 2, AllocationPreview.Rejection.Code.INSUFFICIENT_RESIDUAL);
    }

    @Test
    void shouldTreatSlotDemandAsOneAtomicUnitAndRespectSlotProtection() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).slots(4, 1, 0, 0, 0).build(),
            storage(2).slots(5, 1, 0, 0, 3).build()
        );
        AllocationRequest request = new AllocationRequest(
            new AllocationDemand(18, Map.of(), 2), new AllocationPolicy(List.of()));

        AllocationPreview result = AllocationKernel.preview(storage, request);

        assertEquals(1L, result.selectedStorageId());
        assertEquals(1, result.candidates().getFirst().slotResidual());
        assertTrue(result.candidates().getFirst().capacityResidual().isEmpty());
        assertRejected(result, 2, AllocationPreview.Rejection.Code.SLOT_PROTECTED);
    }

    @Test
    void shouldRejectMissingSlotsAndRespectSlotProtection() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).build(),
            storage(2).slots(5, 1, 0, 0, 3).build()
        );
        AllocationRequest request = new AllocationRequest(
            new AllocationDemand(19, Map.of(), 2), new AllocationPolicy(List.of()));

        AllocationPreview result = AllocationKernel.preview(storage, request);

        assertRejected(result, 1, AllocationPreview.Rejection.Code.SLOT_MISSING);
        assertRejected(result, 2, AllocationPreview.Rejection.Code.SLOT_PROTECTED);
    }

    @Test
    void shouldRunSingleDimensionBfdInsideOneShapeGroup() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("QTY", "8", "0", "0", "0", "0").build(),
            storage(2).capacity("QTY", "7", "0", "0", "0", "0").build()
        );
        List<AllocationRequest> requests = List.of(
            capacityRequest(300, "QTY", "4"),
            capacityRequest(100, "QTY", "5"),
            capacityRequest(200, "QTY", "4")
        );

        AllocationBatchPreview batch = AllocationKernel.previewBatch(storage, requests);

        assertEquals(List.of(100L, 200L, 300L), batch.units().stream()
            .map(AllocationPreview::allocationUnitId).toList());
        assertEquals(2L, byUnit(batch, 100).selectedStorageId());
        assertEquals(1L, byUnit(batch, 200).selectedStorageId());
        assertEquals(1L, byUnit(batch, 300).selectedStorageId());
        assertFalse(batch.partial());
    }

    @Test
    void shouldKeepDifferentUnitsInIndependentGroups() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("QTY", "10", "0", "0", "0", "0").build(),
            storage(2).capacity("WEIGHT", "KG", "10", "0").build()
        );
        List<AllocationRequest> requests = List.of(
            new AllocationRequest(
                new AllocationDemand(201,
                    Map.of("QTY", new AllocationQuantity(new BigDecimal("2"), PCS, 0)), 0),
                new AllocationPolicy(List.of("QTY"))),
            new AllocationRequest(
                new AllocationDemand(202,
                    Map.of("WEIGHT", new AllocationQuantity(new BigDecimal("2"), "KG", 0)), 0),
                new AllocationPolicy(List.of("WEIGHT")))
        );

        AllocationBatchPreview batch = AllocationKernel.previewBatch(storage, requests);

        assertEquals(1L, byUnit(batch, 201).selectedStorageId());
        assertEquals(2L, byUnit(batch, 202).selectedStorageId());
        assertRejected(byUnit(batch, 201), 2, AllocationPreview.Rejection.Code.CAPACITY_MISSING);
    }

    @Test
    void shouldKeepDifferentPrecisionsInIndependentGroups() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("QTY", "1", "0", "0", "0", "0").build(),
            storage(2).capacity("QTY", PCS, "2.0", "0", "0", "0", "0", 1).build()
        );
        List<AllocationRequest> requests = List.of(
            new AllocationRequest(
                new AllocationDemand(203,
                    Map.of("QTY", new AllocationQuantity(new BigDecimal("2.0"), PCS, 1)), 0),
                new AllocationPolicy(List.of("QTY"))),
            new AllocationRequest(
                new AllocationDemand(204,
                    Map.of("QTY", new AllocationQuantity(new BigDecimal("1"), PCS, 0)), 0),
                new AllocationPolicy(List.of("QTY")))
        );

        AllocationBatchPreview batch = AllocationKernel.previewBatch(storage, requests);

        assertEquals(List.of(204L, 203L), batch.units().stream()
            .map(AllocationPreview::allocationUnitId).toList());
        assertEquals(1L, byUnit(batch, 204).selectedStorageId());
        assertEquals(2L, byUnit(batch, 203).selectedStorageId());
    }

    @Test
    void shouldReturnPartialBatchWithPerUnitNoCandidateExplanation() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).capacity("QTY", "10", "0", "0", "0", "0").build()
        );
        List<AllocationRequest> requests = List.of(
            capacityRequest(301, "QTY", "1"),
            capacityRequest(302, "QTY", "20")
        );

        AllocationBatchPreview batch = AllocationKernel.previewBatch(storage, requests);

        assertTrue(batch.partial());
        assertEquals(1L, byUnit(batch, 301).selectedStorageId());
        assertEquals(AllocationPreview.Outcome.NO_CANDIDATE,
            byUnit(batch, 302).outcome());
        assertRejected(byUnit(batch, 302), 1,
            AllocationPreview.Rejection.Code.INSUFFICIENT_RESIDUAL);
    }

    @Test
    void shouldNotPretendTwoStoragesInOneChannelAreIndependent() {
        List<AllocationStorageSnapshot> storage = List.of(
            storage(1).channel(70).capacity("QTY", "100", "0", "0", "0", "0").build(),
            storage(2).channel(70).capacity("QTY", "100", "0", "0", "0", "0").build()
        );
        List<AllocationRequest> requests = List.of(
            capacityRequest(401, "QTY", "1"),
            capacityRequest(402, "QTY", "1")
        );

        AllocationBatchPreview batch = AllocationKernel.previewBatch(storage, requests);

        assertEquals(1L, byUnit(batch, 401).selectedStorageId());
        assertEquals(AllocationPreview.Outcome.NO_CANDIDATE, byUnit(batch, 402).outcome());
        assertRejected(byUnit(batch, 402), 1, AllocationPreview.Rejection.Code.CHANNEL_BUSY);
        assertRejected(byUnit(batch, 402), 2, AllocationPreview.Rejection.Code.CHANNEL_BUSY);
    }

    @Test
    void shouldRejectChannelAlreadyClaimedByPersistentLock() {
        AllocationStorageSnapshot storage =
            storage(1).channel(70).capacity("QTY", "100", "0", "0", "0", "0").build();

        AllocationPreview result = AllocationKernel.preview(
            List.of(storage),
            capacityRequest(403, "QTY", "1"),
            AllocationLimits.DEFAULT,
            Set.of(70L)
        );

        assertEquals(AllocationPreview.Outcome.NO_CANDIDATE, result.outcome());
        assertRejected(result, 1, AllocationPreview.Rejection.Code.CHANNEL_BUSY);
    }

    @Test
    void shouldLeaveFrozenSnapshotUnchangedAfterPreview() {
        AllocationStorageSnapshot snapshot =
            storage(1).capacity("QTY", "10", "0", "0", "0", "0").build();

        AllocationKernel.preview(List.of(snapshot), capacityRequest(501, "QTY", "3"));

        assertEquals(0, snapshot.capacities().get("QTY").locked().compareTo(BigDecimal.ZERO));
        assertEquals(0,
            snapshot.capacities().get("QTY").available().compareTo(new BigDecimal("10")));
    }

    @Test
    void shouldValidateDimensionsAndInputLimits() {
        AllocationStorageSnapshot snapshot =
            storage(1).capacity("QTY", "10", "0", "0", "0", "0").build();
        AllocationKernelException dimensionError = assertThrows(
            AllocationKernelException.class,
            () -> AllocationKernel.preview(
                List.of(snapshot),
                new AllocationRequest(
                    new AllocationDemand(601,
                        Map.of("QTY", quantity("1")), 0),
                    new AllocationPolicy(List.of("QTY", "WEIGHT")))));
        assertEquals(AllocationKernelException.Code.ALLOCATION_DIMENSION_INVALID.name(),
            dimensionError.code());

        AllocationKernelException slotPolicyError = assertThrows(
            AllocationKernelException.class,
            () -> AllocationKernel.preview(
                List.of(snapshot),
                new AllocationRequest(
                    new AllocationDemand(602, Map.of(), 1),
                    new AllocationPolicy(List.of("QTY")))));
        assertEquals(AllocationKernelException.Code.ALLOCATION_DIMENSION_INVALID.name(),
            slotPolicyError.code());

        AllocationKernelException limitError = assertThrows(
            AllocationKernelException.class,
            () -> AllocationKernel.preview(
                List.of(snapshot),
                capacityRequest(603, "QTY", "1"),
                new AllocationLimits(0, 32, 1000)));
        assertEquals(AllocationKernelException.Code.ALLOCATION_LIMIT_INVALID.name(),
            limitError.code());
    }

    @Test
    void shouldEnforceExactScaleAndImmutableDemandShape() {
        assertThrows(AllocationKernelException.class,
            () -> new AllocationQuantity(new BigDecimal("0.0000000000001"), PCS, 13));
        assertThrows(AllocationKernelException.class,
            () -> new AllocationQuantity(new BigDecimal("1.00"), PCS, 0));
        assertThrows(AllocationKernelException.class,
            () -> new AllocationDemand(700, Map.of(), 0));
        assertThrows(AllocationKernelException.class,
            () -> new AllocationDemand(701,
                Map.of("QTY", quantity("1")), 1));

        AllocationPreview result = AllocationKernel.preview(
            List.of(storage(1)
                .capacity("QTY", PCS, "0.000000000002", "0", "0", "0", "0", 12)
                .build()),
            new AllocationRequest(
                new AllocationDemand(702,
                    Map.of("QTY",
                        new AllocationQuantity(new BigDecimal("0.000000000001"), PCS, 12)),
                    0),
                new AllocationPolicy(List.of("QTY"))));

        assertEquals(1L, result.selectedStorageId());
    }

    private static void assertRejected(
        AllocationPreview preview,
        long storageId,
        AllocationPreview.Rejection.Code code
    ) {
        AllocationPreview.Rejection rejection = preview.rejections().stream()
            .filter(item -> item.storageId() == storageId)
            .findFirst()
            .orElseThrow();
        assertEquals(code, rejection.code());
    }

    private static AllocationPreview byUnit(AllocationBatchPreview batch, long allocationUnitId) {
        return batch.units().stream()
            .filter(unit -> unit.allocationUnitId() == allocationUnitId)
            .findFirst()
            .orElseThrow();
    }

    private static AllocationQuantity quantity(String amount) {
        return new AllocationQuantity(new BigDecimal(amount), PCS, 0);
    }

    private static AllocationRequest capacityRequest(
        long allocationUnitId,
        String dimension,
        String amount
    ) {
        return new AllocationRequest(
            new AllocationDemand(allocationUnitId, Map.of(dimension, quantity(amount)), 0),
            new AllocationPolicy(List.of(dimension)));
    }

    private static AllocationCapacityFact capacity(
        String capacity,
        String occupied,
        String locked,
        String held,
        String protectedRemaining
    ) {
        return capacity(capacity, occupied, locked, held, protectedRemaining, PCS, 0);
    }

    private static AllocationCapacityFact capacity(
        String capacity,
        String occupied,
        String locked,
        String held,
        String protectedRemaining,
        String unit,
        int scale
    ) {
        return new AllocationCapacityFact(
            new BigDecimal(capacity), new BigDecimal(occupied), new BigDecimal(locked),
            new BigDecimal(held), new BigDecimal(protectedRemaining), unit, scale);
    }

    private static StorageBuilder storage(long storageId) {
        return new StorageBuilder(storageId);
    }

    private static final class StorageBuilder {
        private final long storageId;
        private final Map<String, AllocationCapacityFact> capacities = new LinkedHashMap<>();
        private boolean enabled = true;
        private boolean safe = true;
        private boolean forbidden;
        private boolean reachable = true;
        private Long channelId;
        private Integer accessOrder = 0;
        private String address;
        private boolean compatibleMaterial;
        private boolean compatibleLogicalScope;
        private AllocationProtection protection = AllocationProtection.NONE;
        private AllocationSlotFact slots;

        private StorageBuilder(long storageId) {
            this.storageId = storageId;
            this.address = "ADDR-" + storageId;
        }

        private StorageBuilder enabled(boolean value) {
            enabled = value;
            return this;
        }

        private StorageBuilder safe(boolean value) {
            safe = value;
            return this;
        }

        private StorageBuilder forbidden(boolean value) {
            forbidden = value;
            return this;
        }

        private StorageBuilder reachable(boolean value) {
            reachable = value;
            return this;
        }

        private StorageBuilder channel(long value) {
            channelId = value;
            return this;
        }

        private StorageBuilder accessOrder(Integer value) {
            accessOrder = value;
            return this;
        }

        private StorageBuilder address(String value) {
            address = value;
            return this;
        }

        private StorageBuilder compatibleMaterial(boolean value) {
            compatibleMaterial = value;
            return this;
        }

        private StorageBuilder compatibleLogicalScope(boolean value) {
            compatibleLogicalScope = value;
            return this;
        }

        private StorageBuilder protection(AllocationProtection value) {
            protection = value;
            return this;
        }

        private StorageBuilder capacity(
            String dimension,
            String capacity,
            String occupied,
            String locked,
            String held,
            String protectedRemaining
        ) {
            capacities.put(dimension,
                AllocationKernelTest.capacity(capacity, occupied, locked, held,
                    protectedRemaining));
            return this;
        }

        private StorageBuilder capacity(
            String dimension,
            String unit,
            String capacity,
            String protectedRemaining
        ) {
            capacities.put(dimension,
                AllocationKernelTest.capacity(capacity, "0", "0", "0",
                    protectedRemaining, unit, 0));
            return this;
        }

        private StorageBuilder capacity(
            String dimension,
            String unit,
            String capacity,
            String occupied,
            String locked,
            String held,
            String protectedRemaining,
            int scale
        ) {
            capacities.put(dimension,
                AllocationKernelTest.capacity(capacity, occupied, locked, held,
                    protectedRemaining, unit, scale));
            return this;
        }

        private StorageBuilder slots(
            int total,
            int occupied,
            int locked,
            int held,
            int protectedFree
        ) {
            slots = new AllocationSlotFact(total, occupied, locked, held, protectedFree);
            return this;
        }

        private AllocationStorageSnapshot build() {
            return new AllocationStorageSnapshot(
                storageId, enabled, safe, forbidden, reachable, channelId, accessOrder,
                address, compatibleMaterial, compatibleLogicalScope, protection,
                capacities, slots);
        }
    }
}
