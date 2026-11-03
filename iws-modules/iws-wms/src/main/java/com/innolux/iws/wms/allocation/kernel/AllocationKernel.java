/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选纯内核
 * @Version      : V1.0
 * @Description  : 硬筛选、兼容/保护/best-fit 排序与批量影子账；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.kernel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 分配候选硬筛选与稳定排序纯内核。
 *
 * <p>输入必须已按真实叶子、规则版本、空间父链、通道成员/阻挡边和实时容量账冻结。
 * 内核只做只读预览：不访问数据库、不创建计划实例、不写锁/占用/预留，也不按地址文本
 * 推断访问顺序。批量影子账只用于解释候选，不产生正式认领。</p>
 */
public final class AllocationKernel {
    private AllocationKernel() {
    }

    public static AllocationPreview preview(
        List<AllocationStorageSnapshot> snapshots,
        AllocationRequest request
    ) {
        return preview(snapshots, request, AllocationLimits.DEFAULT);
    }

    /** 对单个不可拆单元生成确定、解释完整的只读候选。 */
    public static AllocationPreview preview(
        List<AllocationStorageSnapshot> snapshots,
        AllocationRequest request,
        AllocationLimits limits
    ) {
        return preview(snapshots, request, limits, Set.of());
    }

    /**
     * 对单个不可拆单元生成候选，同时把数据库中已被外部任务认领的通道作为硬拒绝。
     *
     * @param snapshots 已冻结的储位事实
     * @param request 分配请求
     * @param limits 输入上限
     * @param claimedChannels 已由其他活动锁认领的通道
     * @return 候选解释
     */
    public static AllocationPreview preview(
        List<AllocationStorageSnapshot> snapshots,
        AllocationRequest request,
        AllocationLimits limits,
        Set<Long> claimedChannels
    ) {
        List<AllocationStorageSnapshot> frozen = validateSnapshots(snapshots, limits);
        validateRequest(request, limits);
        return previewInternal(frozen, request,
            claimedChannels == null ? Set.of() : Set.copyOf(claimedChannels));
    }

    public static AllocationBatchPreview previewBatch(
        List<AllocationStorageSnapshot> snapshots,
        List<AllocationRequest> requests
    ) {
        return previewBatch(snapshots, requests, AllocationLimits.DEFAULT);
    }

    /**
     * 批量候选预览。同容量维度/单位/精度分组；单维组按数量降序 BFD，多维或无容量
     * 组按 allocationUnitId 稳定处理。共享通道在首个候选被预览选中后视为已认领，
     * 后续单元不得把同一通道下的其他储位当成独立资源。
     */
    public static AllocationBatchPreview previewBatch(
        List<AllocationStorageSnapshot> snapshots,
        List<AllocationRequest> requests,
        AllocationLimits limits
    ) {
        List<AllocationStorageSnapshot> frozen = validateSnapshots(snapshots, limits);
        if (requests == null) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_INPUT_NULL);
        }
        if (requests.size() > limits.maxUnits()) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_LIMIT_EXCEEDED);
        }
        Map<String, List<AllocationRequest>> groups = new TreeMap<>();
        Set<Long> unitIds = new HashSet<>();
        for (AllocationRequest request : requests) {
            validateRequest(request, limits);
            if (!unitIds.add(request.demand().allocationUnitId())) {
                throw AllocationKernelException.failure(
                    AllocationKernelException.Code.ALLOCATION_DEMAND_DUPLICATE);
            }
            groups.computeIfAbsent(shapeKey(request.demand()), ignored -> new ArrayList<>())
                .add(request);
        }

        Map<Long, AllocationStorageSnapshot> live = new LinkedHashMap<>();
        for (AllocationStorageSnapshot snapshot : frozen) {
            live.put(snapshot.storageId(), snapshot);
        }
        Set<Long> claimedChannels = new HashSet<>();
        List<AllocationPreview> previews = new ArrayList<>();
        for (List<AllocationRequest> group : groups.values()) {
            group.sort(batchComparator());
            for (AllocationRequest request : group) {
                AllocationPreview preview = previewInternal(live.values(), request, claimedChannels);
                previews.add(preview);
                Long selected = preview.selectedStorageId();
                if (selected == null) {
                    continue;
                }
                AllocationStorageSnapshot storage = live.get(selected);
                if (storage.channelId() != null) {
                    claimedChannels.add(storage.channelId());
                }
                live.put(selected, reserve(storage, request.demand()));
            }
        }
        return new AllocationBatchPreview(previews);
    }

    private static List<AllocationStorageSnapshot> validateSnapshots(
        List<AllocationStorageSnapshot> snapshots,
        AllocationLimits limits
    ) {
        if (snapshots == null || limits == null) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_INPUT_NULL);
        }
        if (snapshots.size() > limits.maxStorages()) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_LIMIT_EXCEEDED);
        }
        List<AllocationStorageSnapshot> sorted = new ArrayList<>(snapshots);
        if (sorted.stream().anyMatch(snapshot -> snapshot == null)) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_INPUT_NULL);
        }
        sorted.sort(Comparator.comparingLong(AllocationStorageSnapshot::storageId));
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).storageId() == sorted.get(index).storageId()) {
                throw AllocationKernelException.failure(
                    AllocationKernelException.Code.ALLOCATION_STORAGE_DUPLICATE);
            }
        }
        return List.copyOf(sorted);
    }

    private static void validateRequest(AllocationRequest request, AllocationLimits limits) {
        if (request == null) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_REQUEST_NULL);
        }
        AllocationDemand demand = request.demand();
        AllocationPolicy policy = request.policy();
        if (demand.quantities().size() > limits.maxDimensions()) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_LIMIT_EXCEEDED);
        }
        if (demand.capacityMode()) {
            Set<String> dimensions = demand.quantities().keySet();
            if (policy.dimensionOrder().size() != dimensions.size()
                || !new HashSet<>(policy.dimensionOrder()).equals(dimensions)) {
                throw AllocationKernelException.failure(
                    AllocationKernelException.Code.ALLOCATION_DIMENSION_INVALID);
            }
        } else if (!policy.dimensionOrder().isEmpty()) {
            throw AllocationKernelException.failure(
                AllocationKernelException.Code.ALLOCATION_DIMENSION_INVALID);
        }
    }

    private static AllocationPreview previewInternal(
        Collection<AllocationStorageSnapshot> snapshots,
        AllocationRequest request,
        Set<Long> claimedChannels
    ) {
        List<AllocationPreview.Candidate> candidates = new ArrayList<>();
        List<AllocationPreview.Rejection> rejections = new ArrayList<>();
        for (AllocationStorageSnapshot snapshot : snapshots) {
            assess(snapshot, request, claimedChannels)
                .appendTo(candidates, rejections);
        }
        candidates.sort(candidateComparator(request));
        List<AllocationPreview.Candidate> ranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            AllocationPreview.Candidate candidate = candidates.get(index);
            ranked.add(new AllocationPreview.Candidate(
                candidate.storageId(), index + 1, candidate.channelId(),
                candidate.accessOrder(), candidate.compatibilityRank(),
                candidate.protectionCostRank(), candidate.protectionReason(),
                candidate.capacityResidual(), candidate.slotResidual()));
        }
        return new AllocationPreview(
            request.demand().allocationUnitId(),
            ranked.isEmpty()
                ? AllocationPreview.Outcome.NO_CANDIDATE
                : AllocationPreview.Outcome.CANDIDATES,
            ranked,
            rejections);
    }

    private static Assessment assess(
        AllocationStorageSnapshot storage,
        AllocationRequest request,
        Set<Long> claimedChannels
    ) {
        if (!storage.enabled()) {
            return Assessment.rejected(storage, AllocationPreview.Rejection.Code.STORAGE_DISABLED,
                "储位未启用");
        }
        if (!storage.allLeavesSafe()) {
            return Assessment.rejected(storage, AllocationPreview.Rejection.Code.SAFETY_BLOCKED,
                "至少一个叶子未通过安全规则");
        }
        if (storage.anyLeafForbidden()) {
            return Assessment.rejected(storage, AllocationPreview.Rejection.Code.FORBIDDEN,
                "至少一个叶子命中禁存规则");
        }
        if (!storage.pathReachable()) {
            return Assessment.rejected(storage, AllocationPreview.Rejection.Code.PATH_UNREACHABLE,
                "显式通道/阻挡路径不可达");
        }
        if (storage.accessOrder() == null) {
            return Assessment.rejected(storage,
                AllocationPreview.Rejection.Code.ACCESS_ORDER_MISSING,
                "显式通道访问顺序未配置");
        }
        if (storage.channelId() != null && claimedChannels.contains(storage.channelId())) {
            return Assessment.rejected(storage, AllocationPreview.Rejection.Code.CHANNEL_BUSY,
                "同一通道已被本批前序预览认领");
        }

        AllocationDemand demand = request.demand();
        Map<String, BigDecimal> residual = new LinkedHashMap<>();
        Integer slotResidual = null;
        if (demand.capacityMode()) {
            for (String dimension : request.policy().dimensionOrder()) {
                AllocationQuantity quantity = demand.quantities().get(dimension);
                AllocationCapacityFact fact = storage.capacities().get(dimension);
                if (fact == null) {
                    return Assessment.rejected(storage,
                        AllocationPreview.Rejection.Code.CAPACITY_MISSING,
                        "缺少容量维度 " + dimension);
                }
                if (!fact.unit().equals(quantity.unit())) {
                    return Assessment.rejected(storage,
                        AllocationPreview.Rejection.Code.UNIT_MISMATCH,
                        "维度 " + dimension + " 单位不一致");
                }
                if (fact.scale() != quantity.scale()) {
                    return Assessment.rejected(storage,
                        AllocationPreview.Rejection.Code.PRECISION_MISMATCH,
                        "维度 " + dimension + " 精度不一致");
                }
                BigDecimal available = fact.available();
                if (available.compareTo(quantity.amount()) < 0) {
                    return Assessment.rejected(storage,
                        AllocationPreview.Rejection.Code.INSUFFICIENT_RESIDUAL,
                        "维度 " + dimension + " 可用余量不足");
                }
                BigDecimal after = available.subtract(quantity.amount());
                if (after.compareTo(fact.protectedRemaining()) < 0) {
                    return Assessment.rejected(storage,
                        AllocationPreview.Rejection.Code.PROTECTED_RESERVE,
                        "维度 " + dimension + " 分配后低于保护余量");
                }
                residual.put(dimension, after);
            }
        } else {
            AllocationSlotFact fact = storage.slots();
            if (fact == null) {
                return Assessment.rejected(storage,
                    AllocationPreview.Rejection.Code.SLOT_MISSING,
                    "缺少实体槽事实");
            }
            int available = fact.available();
            if (available < demand.slots()) {
                return Assessment.rejected(storage,
                    AllocationPreview.Rejection.Code.INSUFFICIENT_SLOTS,
                    "实体槽余量不足");
            }
            int after = available - demand.slots();
            if (after < fact.protectedFreeSlots()) {
                return Assessment.rejected(storage,
                    AllocationPreview.Rejection.Code.SLOT_PROTECTED,
                    "分配后低于实体槽保护量");
            }
            slotResidual = after;
        }
        int compatibilityRank = storage.compatibleMaterialPresent()
            ? 0 : storage.compatibleLogicalScopePresent() ? 1 : 2;
        return Assessment.candidate(new AllocationPreview.Candidate(
            storage.storageId(), 1, storage.channelId(), storage.accessOrder(),
            compatibilityRank, storage.protection().costRank(),
            storage.protection().reason(), residual, slotResidual));
    }

    private static Comparator<AllocationPreview.Candidate> candidateComparator(
        AllocationRequest request
    ) {
        Comparator<AllocationPreview.Candidate> comparator =
            Comparator.comparingInt(AllocationPreview.Candidate::compatibilityRank)
                .thenComparingInt(AllocationPreview.Candidate::protectionCostRank);
        for (String dimension : request.policy().dimensionOrder()) {
            comparator = comparator.thenComparing(
                candidate -> candidate.capacityResidual().get(dimension));
        }
        if (!request.demand().capacityMode()) {
            comparator = comparator.thenComparing(
                AllocationPreview.Candidate::slotResidual);
        }
        return comparator.thenComparingLong(AllocationPreview.Candidate::storageId);
    }

    private static Comparator<AllocationRequest> batchComparator() {
        return (left, right) -> {
            AllocationDemand leftDemand = left.demand();
            AllocationDemand rightDemand = right.demand();
            if (leftDemand.capacityMode() && left.policy().dimensionOrder().size() == 1) {
                String dimension = left.policy().dimensionOrder().getFirst();
                int quantityOrder = rightDemand.quantities().get(dimension).amount()
                    .compareTo(leftDemand.quantities().get(dimension).amount());
                if (quantityOrder != 0) {
                    return quantityOrder;
                }
            }
            return Long.compare(leftDemand.allocationUnitId(),
                rightDemand.allocationUnitId());
        };
    }

    private static String shapeKey(AllocationDemand demand) {
        if (!demand.capacityMode()) {
            return "SLOT";
        }
        List<String> dimensions = new ArrayList<>(demand.quantities().keySet());
        dimensions.sort(String::compareTo);
        StringBuilder key = new StringBuilder("CAP");
        for (String dimension : dimensions) {
            AllocationQuantity quantity = demand.quantities().get(dimension);
            key.append('|').append(dimension.length()).append(':').append(dimension)
                .append('@').append(quantity.unit().length()).append(':')
                .append(quantity.unit()).append('#').append(quantity.scale());
        }
        return key.toString();
    }

    private static AllocationStorageSnapshot reserve(
        AllocationStorageSnapshot storage,
        AllocationDemand demand
    ) {
        if (demand.capacityMode()) {
            Map<String, AllocationCapacityFact> capacities =
                new HashMap<>(storage.capacities());
            demand.quantities().forEach((dimension, quantity) ->
                capacities.computeIfPresent(dimension, (ignored, fact) ->
                    fact.withLockedAdded(quantity.amount())));
            return copyWith(storage, capacities, storage.slots());
        }
        return copyWith(storage, storage.capacities(),
            storage.slots().withLockedAdded(demand.slots()));
    }

    private static AllocationStorageSnapshot copyWith(
        AllocationStorageSnapshot source,
        Map<String, AllocationCapacityFact> capacities,
        AllocationSlotFact slots
    ) {
        return new AllocationStorageSnapshot(
            source.storageId(), source.enabled(), source.allLeavesSafe(),
            source.anyLeafForbidden(), source.pathReachable(), source.channelId(),
            source.accessOrder(), source.addressText(), source.compatibleMaterialPresent(),
            source.compatibleLogicalScopePresent(), source.protection(), capacities, slots);
    }

    private sealed interface Assessment permits CandidateAssessment, RejectionAssessment {
        void appendTo(
            List<AllocationPreview.Candidate> candidates,
            List<AllocationPreview.Rejection> rejections
        );

        static Assessment candidate(AllocationPreview.Candidate candidate) {
            return new CandidateAssessment(candidate);
        }

        static Assessment rejected(
            AllocationStorageSnapshot storage,
            AllocationPreview.Rejection.Code code,
            String detail
        ) {
            return new RejectionAssessment(
                new AllocationPreview.Rejection(storage.storageId(), code, detail));
        }
    }

    private record CandidateAssessment(AllocationPreview.Candidate candidate)
        implements Assessment {
        @Override
        public void appendTo(
            List<AllocationPreview.Candidate> candidates,
            List<AllocationPreview.Rejection> rejections
        ) {
            candidates.add(candidate);
        }
    }

    private record RejectionAssessment(AllocationPreview.Rejection rejection)
        implements Assessment {
        @Override
        public void appendTo(
            List<AllocationPreview.Candidate> candidates,
            List<AllocationPreview.Rejection> rejections
        ) {
            rejections.add(rejection);
        }
    }
}
