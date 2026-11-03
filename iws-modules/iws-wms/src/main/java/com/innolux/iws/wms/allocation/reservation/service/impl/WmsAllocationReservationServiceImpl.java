/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : 锁通道/储位、当前读实时账、持久候选并原子提交容量或实体槽锁；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.kernel.AllocationCapacityFact;
import com.innolux.iws.wms.allocation.kernel.AllocationDemand;
import com.innolux.iws.wms.allocation.kernel.AllocationKernel;
import com.innolux.iws.wms.allocation.kernel.AllocationLimits;
import com.innolux.iws.wms.allocation.kernel.AllocationPreview;
import com.innolux.iws.wms.allocation.kernel.AllocationQuantity;
import com.innolux.iws.wms.allocation.kernel.AllocationSlotFact;
import com.innolux.iws.wms.allocation.kernel.AllocationStorageSnapshot;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationBusinessContext;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReservationResult;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationSnapshotInput;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import com.innolux.iws.wms.allocation.reservation.space.WmsAllocationSpaceReader;
import com.innolux.iws.wms.allocation.reservation.space.WmsAllocationSpaceReader.Membership;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilitySnapshot;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilitySnapshotService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 分配锁位短事务实现。
 *
 * <p>固定锁序为通道 ID 升序、储位 ID 升序；取得行锁后再以当前读复核成员归属、
 * 容量、占用和活动锁。成员/顺序/revision 已变化时拒绝沿用旧候选，由调用者重新
 * 发起同一计划事件。</p>
 */
@Service
public class WmsAllocationReservationServiceImpl implements WmsAllocationReservationService {

    private static final Pattern EFFECT_KEY_PATTERN = Pattern.compile("^WMS:[0-9a-f]{64}$");

    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private static final int DIRECT_STORAGE_ACCESS_ORDER = 0;

    private final JdbcTemplate jdbcTemplate;

    private final WmsAllocationSpaceReader spaceReader;

    private final IdentifierGenerator identifierGenerator;

    private final JsonMapper jsonMapper;

    private final WmsChannelReachabilitySnapshotService reachabilitySnapshotService;

    private final NamedParameterJdbcTemplate namedJdbc;

    public WmsAllocationReservationServiceImpl(
        JdbcTemplate jdbcTemplate,
        WmsAllocationSpaceReader spaceReader,
        IdentifierGenerator identifierGenerator,
        JsonMapper jsonMapper,
        WmsChannelReachabilitySnapshotService reachabilitySnapshotService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.spaceReader = spaceReader;
        this.identifierGenerator = identifierGenerator;
        this.jsonMapper = jsonMapper;
        this.reachabilitySnapshotService = reachabilitySnapshotService;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsAllocationReservationResult evaluate(WmsAllocationReserveCommand command) {
        validate(command);
        WmsAllocationDecisionRow existing = selectDecisionByEffectKeyForUpdate(command.effectKey());
        if (existing != null) {
            return replay(command, existing);
        }

        List<Long> storageIds = sortedStorageIds(command);
        Map<Long, Membership> initial = spaceReader.readInitial(storageIds);
        List<Long> channelIds = sortedChannelIds(initial);
        AllocationPreview preview = preview(
            command,
            storageIds,
            initial,
            loadChannels(channelIds, false),
            loadStorages(storageIds, false),
            loadCapacities(storageIds, command.request(), false),
            loadOccupancies(storageIds, command.request(), false),
            loadAllocationLocks(storageIds, false),
            loadActiveChannels(channelIds, false),
            false
        );

        long decisionId = nextId();
        insertDecision(command, decisionId, preview);
        Map<Long, Long> candidateIds = insertCandidates(decisionId, preview);
        if (preview.selectedStorageId() == null) {
            updateDecisionResult(decisionId, "NO_CANDIDATE");
            return result(decisionId, "NO_CANDIDATE", null, null, List.of(), List.of());
        }
        return result(
            decisionId,
            "EVALUATED",
            preview.selectedStorageId(),
            candidateIds.get(preview.selectedStorageId()),
            List.of(),
            List.of()
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsAllocationReservationResult reserve(WmsAllocationReserveCommand command) {
        validate(command);
        List<Long> storageIds = sortedStorageIds(command);
        WmsAllocationDecisionRow existing = selectDecisionByEffectKeyForUpdate(command.effectKey());
        if (existing != null) {
            // 无论决策处于 EVALUATED 还是终态，稳定键身份都必须先通过同一输入快照校验。
            replay(command, existing);
            if (!"EVALUATED".equals(existing.result())) {
                return buildResult(existing);
            }
        }
        if (existing == null) {
            evaluate(command);
            existing = selectDecisionByEffectKeyForUpdate(command.effectKey());
            if (!"EVALUATED".equals(existing.result())) {
                return replay(command, existing);
            }
        }

        Map<Long, Membership> initial = spaceReader.readInitial(storageIds);
        List<Long> channelIds = sortedChannelIds(initial);
        Map<Long, ChannelRow> channels = lockChannels(channelIds);
        Map<Long, StorageRow> storages = lockStorages(storageIds);
        Map<Long, Membership> current = spaceReader.readCurrentForUpdate(storageIds);
        assertLockDomainUnchanged(initial, current);

        Map<Long, WmsAllocationSnapshotInput> inputs = command.storages().stream()
            .collect(Collectors.toMap(
                WmsAllocationSnapshotInput::storageId,
                Function.identity(),
                (left, right) -> {
                    throw invalid("候选储位重复: " + left.storageId());
                },
                LinkedHashMap::new
            ));
        Map<Long, List<CapacityRow>> capacities = lockCapacities(storageIds, command.request());
        Map<Long, List<OccupancyRow>> occupancies = lockOccupancies(storageIds, command.request());
        Map<Long, List<LockRow>> activeLocks = lockAllocationLocks(storageIds);
        Set<Long> busyChannels = lockActiveChannels(channelIds);

        AllocationPreview preview = preview(
            command,
            storageIds,
            current,
            channels,
            storages,
            capacities,
            occupancies,
            activeLocks,
            busyChannels,
            true
        );

        long decisionId = existing.decisionId();
        replaceEvaluatedCandidates(decisionId, preview);
        Map<Long, Long> candidateIds = insertCandidates(decisionId, preview);
        if (preview.selectedStorageId() == null) {
            updateDecisionResult(decisionId, "NO_CANDIDATE");
            return result(decisionId, "NO_CANDIDATE", null, null, List.of(), List.of());
        }

        long selectedStorageId = preview.selectedStorageId();
        Membership membership = current.get(selectedStorageId);
        Long channelLockId = null;
        if (membership != null) {
            ChannelRow channel = channels.get(membership.channelId());
            channelLockId = nextId();
            insertChannelLock(command, decisionId, channelLockId, membership, channel);
        }
        List<Long> allocationLockIds = insertAllocationLocks(
            command,
            decisionId,
            candidateIds.get(selectedStorageId),
            selectedStorageId,
            channelLockId,
            inputs.get(selectedStorageId),
            activeLocks.getOrDefault(selectedStorageId, List.of())
        );
        updateDecisionResult(decisionId, "LOCKED");
        verifyPersisted(command, decisionId, selectedStorageId, channelLockId, allocationLockIds);
        return result(
            decisionId,
            "LOCKED",
            selectedStorageId,
            candidateIds.get(selectedStorageId),
            channelLockId == null ? List.of() : List.of(channelLockId),
            allocationLockIds
        );
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public WmsAllocationReservationResult verify(String effectKey) {
        if (effectKey == null || !EFFECT_KEY_PATTERN.matcher(effectKey).matches()) {
            throw invalid("effectKey必须为WMS:加小写64位SHA-256");
        }
        WmsAllocationDecisionRow decision = selectDecisionByEffectKey(effectKey);
        if (decision == null) {
            throw error(ERROR_VERIFY_FAILED, "未找到分配决策: " + effectKey,
                HttpStatus.HTTP_NOT_FOUND);
        }
        return buildResult(decision);
    }

    private void validate(WmsAllocationReserveCommand command) {
        if (command == null || command.business() == null || command.request() == null
            || command.storages() == null || command.storages().isEmpty()) {
            throw invalid("分配锁位命令、父业务、请求和候选储位不能为空");
        }
        if (command.effectKey() == null || !EFFECT_KEY_PATTERN.matcher(command.effectKey()).matches()) {
            throw invalid("effectKey必须为WMS:加小写64位SHA-256");
        }
        if (command.inputHash() == null || !HASH_PATTERN.matcher(command.inputHash()).matches()
            || command.spaceSnapshotHash() == null
            || !HASH_PATTERN.matcher(command.spaceSnapshotHash()).matches()
            || command.capacitySnapshotHash() == null
            || !HASH_PATTERN.matcher(command.capacitySnapshotHash()).matches()) {
            throw invalid("inputHash、spaceSnapshotHash和capacitySnapshotHash必须为小写64位SHA-256");
        }
        WmsAllocationBusinessContext business = command.business();
        requireText(business.businessType(), "businessType", 64);
        requireText(business.businessName(), "businessName", 128);
        requirePositive(business.businessId(), "businessId");
        optionalText(business.businessNo(), "businessNo", 100);
        if (business.businessLineId() != null && business.businessLineId() <= 0) {
            throw invalid("businessLineId必须为正整数");
        }
        requirePositive(business.allocationUnitId(), "allocationUnitId");
        requirePositive(business.planInstanceId(), "planInstanceId");
        requireText(business.taskKey(), "taskKey", 96);
        if (business.roundNo() < 1) {
            throw invalid("roundNo必须大于等于1");
        }
        if (command.rulePackageVersionId() <= 0 || command.logicalScopeVersionId() <= 0) {
            throw invalid("规则包版本和逻辑归属版本必须为正整数");
        }
        if (command.request().demand().allocationUnitId() != business.allocationUnitId()) {
            throw invalid("请求分配单元与父业务分配单元不一致");
        }
        Set<Long> storageIds = new HashSet<>();
        for (WmsAllocationSnapshotInput input : command.storages()) {
            if (input == null || input.storageId() <= 0 || !storageIds.add(input.storageId())) {
                throw invalid("候选储位为空、非正或重复");
            }
            if (input.protection() == null) {
                throw invalid("候选储位缺少保护事实: " + input.storageId());
            }
            if (input.slotKeys().stream().anyMatch(key -> key == null || key.isBlank())
                || new HashSet<>(input.slotKeys()).size() != input.slotKeys().size()) {
                throw invalid("实体槽键为空或重复: " + input.storageId());
            }
            if (!input.slotKeys().containsAll(input.occupiedSlotKeys())) {
                throw invalid("已占用实体槽不在物理槽目录内: " + input.storageId());
            }
        }
        if (!command.request().demand().capacityMode()) {
            for (WmsAllocationSnapshotInput input : command.storages()) {
                if (input.slotKeys().isEmpty()) {
                    throw invalid("实体槽模式缺少物理槽目录: " + input.storageId());
                }
            }
        }
    }

    private WmsAllocationReservationResult replay(
        WmsAllocationReserveCommand command,
        WmsAllocationDecisionRow existing
    ) {
        WmsAllocationBusinessContext business = command.business();
        if (!Objects.equals(existing.inputHash(), command.inputHash())
            || !Objects.equals(existing.businessType(), business.businessType())
            || !Objects.equals(existing.businessId(), business.businessId())
            || !Objects.equals(existing.businessLineId(), business.businessLineId())
            || !Objects.equals(existing.allocationUnitId(), business.allocationUnitId())
            || !Objects.equals(existing.roundNo(), business.roundNo())
            || !Objects.equals(existing.taskKey(), business.taskKey())
            || !Objects.equals(existing.planInstanceId(), business.planInstanceId())) {
            throw error(ERROR_EFFECT_KEY_CONFLICT,
                "同一effectKey已绑定不同分配输入", HttpStatus.HTTP_CONFLICT);
        }
        return buildResult(existing);
    }

    private List<Long> sortedStorageIds(WmsAllocationReserveCommand command) {
        return command.storages().stream()
            .map(WmsAllocationSnapshotInput::storageId)
            .sorted()
            .toList();
    }

    private List<Long> sortedChannelIds(Map<Long, Membership> memberships) {
        return memberships.values().stream()
            .map(Membership::channelId)
            .distinct()
            .sorted()
            .toList();
    }

    private AllocationPreview preview(
        WmsAllocationReserveCommand command,
        List<Long> storageIds,
        Map<Long, Membership> memberships,
        Map<Long, ChannelRow> channels,
        Map<Long, StorageRow> storages,
        Map<Long, List<CapacityRow>> capacities,
        Map<Long, List<OccupancyRow>> occupancies,
        Map<Long, List<LockRow>> activeLocks,
        Set<Long> busyChannels,
        boolean currentRead
    ) {
        Map<Long, WmsAllocationSnapshotInput> inputs = command.storages().stream()
            .collect(Collectors.toMap(
                WmsAllocationSnapshotInput::storageId,
                Function.identity(),
                (left, right) -> {
                    throw invalid("候选储位重复: " + left.storageId());
                },
                LinkedHashMap::new
            ));
        Map<Long, WmsChannelReachabilitySnapshot> reachabilitySnapshots =
            loadReachabilitySnapshots(memberships, channels, currentRead);
        List<AllocationStorageSnapshot> snapshots = storageIds.stream()
            .map(storageId -> snapshot(
                inputs.get(storageId),
                storages.get(storageId),
                memberships.get(storageId),
                memberships.get(storageId) == null
                    ? null : channels.get(memberships.get(storageId).channelId()),
                capacities.getOrDefault(storageId, List.of()),
                occupancies.getOrDefault(storageId, List.of()),
                activeLocks.getOrDefault(storageId, List.of()),
                command.request(),
                reachabilitySnapshots
            ))
            .toList();
        return AllocationKernel.preview(
            snapshots,
            command.request(),
            AllocationLimits.DEFAULT,
            busyChannels
        );
    }

    private Map<Long, WmsChannelReachabilitySnapshot> loadReachabilitySnapshots(
        Map<Long, Membership> memberships,
        Map<Long, ChannelRow> channels,
        boolean currentRead
    ) {
        Map<Long, WmsChannelReachabilitySnapshot> snapshots = new LinkedHashMap<>();
        for (Long channelId : sortedChannelIds(memberships)) {
            WmsChannelReachabilitySnapshot snapshot = currentRead
                ? reachabilitySnapshotService.snapshotForUpdate(channelId)
                : reachabilitySnapshotService.snapshot(channelId);
            ChannelRow channel = channels.get(channelId);
            if (channel == null
                || snapshot.channelRevision() != channel.revision()
                || !Objects.equals(snapshot.status(), channel.status())) {
                throw retryRequired("通道 " + channelId + " 的空间事实在读取时发生变化");
            }
            snapshots.put(channelId, snapshot);
        }
        return snapshots;
    }

    private Map<Long, StorageRow> lockStorages(List<Long> storageIds) {
        return loadStorages(storageIds, true);
    }

    private Map<Long, StorageRow> loadStorages(List<Long> storageIds, boolean forUpdate) {
        String sql = """
            SELECT storage_id, storage_code, status, disable_status, del_flag, version
            FROM wms_storage
            WHERE storage_id IN (:storageIds)
            ORDER BY storage_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<StorageRow> rows = namedJdbc.query(sql, Map.of("storageIds", storageIds),
            (result, index) -> new StorageRow(
            result.getLong("storage_id"),
            result.getString("storage_code"),
            result.getInt("status"),
            result.getInt("disable_status"),
            result.getString("del_flag"),
            result.getInt("version")
        ));
        if (rows.size() != storageIds.size()) {
            throw invalid("候选储位不存在或不可见");
        }
        return rows.stream().collect(Collectors.toMap(
            StorageRow::storageId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, ChannelRow> lockChannels(List<Long> channelIds) {
        return loadChannels(channelIds, true);
    }

    private Map<Long, ChannelRow> loadChannels(List<Long> channelIds, boolean forUpdate) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
            SELECT channel_id, status, revision
            FROM wms_access_channel
            WHERE channel_id IN (:channelIds)
            ORDER BY channel_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<ChannelRow> rows = namedJdbc.query(sql, Map.of("channelIds", channelIds),
            (result, index) -> new ChannelRow(
            result.getLong("channel_id"),
            result.getString("status"),
            result.getInt("revision")
        ));
        if (rows.size() != channelIds.size()) {
            throw retryRequired("通道在锁前被删除或替换");
        }
        return rows.stream().collect(Collectors.toMap(
            ChannelRow::channelId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private Set<Long> lockActiveChannels(List<Long> channelIds) {
        return loadActiveChannels(channelIds, true);
    }

    private Set<Long> loadActiveChannels(List<Long> channelIds, boolean forUpdate) {
        if (channelIds.isEmpty()) {
            return Set.of();
        }
        String sql = """
            SELECT channel_id
            FROM wms_access_channel_lock
            WHERE status = 'LOCKED' AND channel_id IN (:channelIds)
            ORDER BY channel_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<Long> rows = namedJdbc.queryForList(
            sql, Map.of("channelIds", channelIds), Long.class);
        return Set.copyOf(rows);
    }

    private Map<Long, List<CapacityRow>> lockCapacities(
        List<Long> storageIds,
        com.innolux.iws.wms.allocation.kernel.AllocationRequest request
    ) {
        return loadCapacities(storageIds, request, true);
    }

    private Map<Long, List<CapacityRow>> loadCapacities(
        List<Long> storageIds,
        com.innolux.iws.wms.allocation.kernel.AllocationRequest request,
        boolean forUpdate
    ) {
        if (!request.demand().capacityMode()) {
            return Map.of();
        }
        Set<String> dimensions = request.demand().quantities().keySet();
        String sql = """
            SELECT capacity_id, storage_id, dimension_key, capacity_value, unit, scale
            FROM wms_storage_capacity
            WHERE storage_id IN (:storageIds)
              AND dimension_key IN (:dimensions)
              AND active_flag = 1
            ORDER BY storage_id, dimension_key
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<CapacityRow> rows = namedJdbc.query(
            sql, Map.of("storageIds", storageIds, "dimensions", dimensions),
            (result, index) -> new CapacityRow(
                result.getLong("capacity_id"),
                result.getLong("storage_id"),
                result.getString("dimension_key"),
                result.getBigDecimal("capacity_value"),
                result.getString("unit"),
                result.getInt("scale")
            ));
        return rows.stream().collect(Collectors.groupingBy(
            CapacityRow::storageId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, List<OccupancyRow>> lockOccupancies(
        List<Long> storageIds,
        com.innolux.iws.wms.allocation.kernel.AllocationRequest request
    ) {
        return loadOccupancies(storageIds, request, true);
    }

    private Map<Long, List<OccupancyRow>> loadOccupancies(
        List<Long> storageIds,
        com.innolux.iws.wms.allocation.kernel.AllocationRequest request,
        boolean forUpdate
    ) {
        if (!request.demand().capacityMode()) {
            return Map.of();
        }
        Set<String> dimensions = request.demand().quantities().keySet();
        String sql = """
            SELECT occupancy_id, storage_id, dimension_key, occupied_qty
            FROM wms_storage_occupancy
            WHERE storage_id IN (:storageIds)
              AND dimension_key IN (:dimensions)
              AND status = 'ACTIVE'
            ORDER BY storage_id, occupancy_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<OccupancyRow> rows = namedJdbc.query(
            sql, Map.of("storageIds", storageIds, "dimensions", dimensions),
            (result, index) -> new OccupancyRow(
                result.getLong("occupancy_id"),
                result.getLong("storage_id"),
                result.getString("dimension_key"),
                result.getBigDecimal("occupied_qty")
            ));
        return rows.stream().collect(Collectors.groupingBy(
            OccupancyRow::storageId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, List<LockRow>> lockAllocationLocks(List<Long> storageIds) {
        return loadAllocationLocks(storageIds, true);
    }

    private Map<Long, List<LockRow>> loadAllocationLocks(
        List<Long> storageIds,
        boolean forUpdate
    ) {
        String sql = """
            SELECT allocation_lock_id, storage_id, dimension_key, lock_kind, slot_key, locked_qty
            FROM wms_allocation_lock
            WHERE storage_id IN (:storageIds) AND status = 'LOCKED'
            ORDER BY storage_id, allocation_lock_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<LockRow> rows = namedJdbc.query(sql, Map.of("storageIds", storageIds),
            (result, index) -> new LockRow(
            result.getLong("allocation_lock_id"),
            result.getLong("storage_id"),
            result.getString("dimension_key"),
            result.getString("lock_kind"),
            result.getString("slot_key"),
            result.getBigDecimal("locked_qty")
        ));
        return rows.stream().collect(Collectors.groupingBy(
            LockRow::storageId, LinkedHashMap::new, Collectors.toList()));
    }

    private AllocationStorageSnapshot snapshot(
        WmsAllocationSnapshotInput input,
        StorageRow storage,
        Membership membership,
        ChannelRow channel,
        List<CapacityRow> capacityRows,
        List<OccupancyRow> occupancyRows,
        List<LockRow> lockRows,
        com.innolux.iws.wms.allocation.kernel.AllocationRequest request,
        Map<Long, WmsChannelReachabilitySnapshot> reachabilitySnapshots
    ) {
        AllocationDemand demand = request.demand();
        boolean enabled = storage != null && storage.disableStatus() == 0
            && "0".equals(storage.delFlag()) && storage.status() != 3;
        boolean channelActive = membership == null
            || (channel != null && "ACTIVE".equals(channel.status()));
        boolean pathReachable = input.pathReachable() && channelActive;
        if (membership != null) {
            WmsChannelReachabilitySnapshot reachability = reachabilitySnapshots.get(
                membership.channelId());
            pathReachable = pathReachable
                && reachability != null
                && reachability.reachability().reachable(input.storageId());
        }
        Integer accessOrder = membership == null
            ? DIRECT_STORAGE_ACCESS_ORDER : membership.accessOrder();
        Long channelId = membership == null ? null : membership.channelId();

        Map<String, AllocationCapacityFact> capacities = new LinkedHashMap<>();
        if (demand.capacityMode()) {
            Map<String, CapacityRow> byDimension = capacityRows.stream()
                .collect(Collectors.toMap(CapacityRow::dimensionKey, Function.identity()));
            for (String dimension : request.policy().dimensionOrder()) {
                CapacityRow capacity = byDimension.get(dimension);
                if (capacity == null) {
                    continue;
                }
                AllocationQuantity quantity = demand.quantities().get(dimension);
                if (!quantity.unit().equals(capacity.unit())
                    || quantity.scale() != capacity.scale()) {
                    capacities.put(dimension, new AllocationCapacityFact(
                        scaleExact(capacity.capacityValue(), capacity.scale()),
                        zero(capacity.scale()),
                        zero(capacity.scale()),
                        zero(capacity.scale()),
                        zero(capacity.scale()),
                        capacity.unit(),
                        capacity.scale()
                    ));
                    continue;
                }
                BigDecimal occupied = occupancyRows.stream()
                    .filter(row -> dimension.equals(row.dimensionKey()))
                    .map(row -> scaleExact(row.occupiedQty(), capacity.scale()))
                    .reduce(zero(capacity.scale()), BigDecimal::add);
                BigDecimal locked = lockRows.stream()
                    .filter(row -> "CAPACITY_SHARE".equals(row.lockKind()))
                    .filter(row -> dimension.equals(row.dimensionKey()))
                    .map(row -> scaleExact(row.lockedQty(), capacity.scale()))
                    .reduce(zero(capacity.scale()), BigDecimal::add);
                BigDecimal protectedRemaining = input.protectedRemaining()
                    .getOrDefault(dimension, zero(capacity.scale()));
                capacities.put(dimension, new AllocationCapacityFact(
                    scaleExact(capacity.capacityValue(), capacity.scale()),
                    occupied,
                    locked,
                    zero(capacity.scale()),
                    scaleExact(protectedRemaining, capacity.scale()),
                    capacity.unit(),
                    capacity.scale()
                ));
            }
        }

        AllocationSlotFact slots = null;
        if (!demand.capacityMode()) {
            List<String> slotKeys = input.slotKeys().stream().sorted().toList();
            List<String> allocatableSlotKeys = slotKeys.stream()
                .filter(key -> !input.occupiedSlotKeys().contains(key))
                .toList();
            Set<String> owned = new HashSet<>(allocatableSlotKeys);
            Set<String> locked = lockRows.stream()
                .filter(row -> "ENTITY_SLOT".equals(row.lockKind()))
                .map(LockRow::slotKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            if (!new HashSet<>(slotKeys).containsAll(locked)
                || locked.stream().anyMatch(input.occupiedSlotKeys()::contains)) {
                throw error(ERROR_LOCK_DOMAIN_CHANGED,
                    "储位存在物理槽目录外的活动实体槽锁: " + input.storageId(),
                    HttpStatus.HTTP_CONFLICT);
            }
            slots = new AllocationSlotFact(
                allocatableSlotKeys.size(),
                0,
                (int) locked.stream().filter(owned::contains).count(),
                0,
                input.protectedFreeSlots()
            );
        }
        return new AllocationStorageSnapshot(
            input.storageId(),
            enabled,
            input.allLeavesSafe(),
            input.anyLeafForbidden(),
            pathReachable,
            channelId,
            accessOrder,
            storage == null ? null : storage.storageCode(),
            input.compatibleMaterialPresent(),
            input.compatibleLogicalScopePresent(),
            input.protection(),
            capacities,
            slots
        );
    }

    private void assertLockDomainUnchanged(
        Map<Long, Membership> initial,
        Map<Long, Membership> current
    ) {
        Set<Long> storageIds = new LinkedHashSet<>(initial.keySet());
        storageIds.addAll(current.keySet());
        for (Long storageId : storageIds) {
            Membership before = initial.get(storageId);
            Membership after = current.get(storageId);
            if (!sameMembership(before, after)) {
                throw retryRequired("储位 " + storageId + " 的通道成员或锁域已变化");
            }
        }
    }

    private boolean sameMembership(Membership left, Membership right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.storageId() == right.storageId()
            && Objects.equals(left.memberId(), right.memberId())
            && left.channelId() == right.channelId()
            && left.accessOrder() == right.accessOrder()
            && left.memberRevision() == right.memberRevision()
            && left.channelRevision() == right.channelRevision()
            && Objects.equals(left.channelStatus(), right.channelStatus());
    }

    private void insertDecision(
        WmsAllocationReserveCommand command,
        long decisionId,
        AllocationPreview preview
    ) {
        WmsAllocationBusinessContext business = command.business();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eligible", preview.candidates().size());
        summary.put("rejected", preview.rejections().size());
        summary.put("selectedStorageId", preview.selectedStorageId());
        summary.put("outcome", preview.outcome().name());
        jdbcTemplate.update("""
            INSERT INTO wms_allocation_decision (
              decision_id, business_type, business_name, business_id, business_no,
              business_line_id, allocation_unit_id, round_no, phase, task_key,
              plan_instance_id, effect_key, input_hash, rule_package_version_id,
              logical_scope_version_id, space_snapshot_hash, capacity_snapshot_hash,
              candidate_summary_json, result, result_reason, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'LOCK', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EVALUATED', ?, 1)
            """,
            decisionId,
            business.businessType(),
            business.businessName(),
            business.businessId(),
            business.businessNo(),
            business.businessLineId(),
            business.allocationUnitId(),
            business.roundNo(),
            business.taskKey(),
            business.planInstanceId(),
            command.effectKey(),
            command.inputHash(),
            command.rulePackageVersionId(),
            command.logicalScopeVersionId(),
            command.spaceSnapshotHash(),
            command.capacitySnapshotHash(),
            jsonMapper.writeValueAsString(summary),
            preview.selectedStorageId() == null ? "无可用候选" : null
        );
    }

    private Map<Long, Long> insertCandidates(long decisionId, AllocationPreview preview) {
        Map<Long, Long> ids = new LinkedHashMap<>();
        for (AllocationPreview.Candidate candidate : preview.candidates()) {
            long candidateId = nextId();
            ids.put(candidate.storageId(), candidateId);
            List<Object> sortKey = new ArrayList<>();
            sortKey.add(candidate.compatibilityRank());
            sortKey.add(candidate.protectionCostRank());
            if (!candidate.capacityResidual().isEmpty()) {
                candidate.capacityResidual().forEach((dimension, value) -> {
                    sortKey.add(dimension);
                    sortKey.add(value);
                });
            } else {
                sortKey.add("SLOT");
                sortKey.add(candidate.slotResidual());
            }
            sortKey.add(candidate.storageId());
            Map<String, Object> residual = new LinkedHashMap<>();
            if (!candidate.capacityResidual().isEmpty()) {
                residual.putAll(candidate.capacityResidual());
            } else {
                residual.put("SLOT", candidate.slotResidual());
            }
            jdbcTemplate.update("""
                INSERT INTO wms_allocation_candidate (
                  candidate_id, decision_id, storage_id, rank_no, eligible_flag, selected_flag,
                  sort_key_json, residual_vector_json, protection_reason, block_reason
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, NULL)
                """,
                candidateId,
                decisionId,
                candidate.storageId(),
                candidate.rank(),
                candidate.rank() == 1 ? 1 : 0,
                jsonMapper.writeValueAsString(sortKey),
                jsonMapper.writeValueAsString(residual),
                candidate.protectionReason()
            );
        }
        int rank = preview.candidates().size() + 1;
        for (AllocationPreview.Rejection rejection : preview.rejections()) {
            long candidateId = nextId();
            ids.put(rejection.storageId(), candidateId);
            jdbcTemplate.update("""
                INSERT INTO wms_allocation_candidate (
                  candidate_id, decision_id, storage_id, rank_no, eligible_flag, selected_flag,
                  sort_key_json, residual_vector_json, protection_reason, block_reason
                ) VALUES (?, ?, ?, ?, 0, 0, ?, '{}', NULL, ?)
                """,
                candidateId,
                decisionId,
                rejection.storageId(),
                rank++,
                jsonMapper.writeValueAsString(List.of("REJECTED", rejection.code().name())),
                rejection.detail()
            );
        }
        return ids;
    }

    private void insertChannelLock(
        WmsAllocationReserveCommand command,
        long decisionId,
        long channelLockId,
        Membership membership,
        ChannelRow channel
    ) {
        List<Long> pathMemberIds = jdbcTemplate.queryForList("""
            SELECT member_id
            FROM wms_storage_access_member
            WHERE channel_id = ?
            ORDER BY access_order, member_id
            FOR UPDATE
            """, Long.class, membership.channelId());
        WmsAllocationBusinessContext business = command.business();
        jdbcTemplate.update("""
            INSERT INTO wms_access_channel_lock (
              channel_lock_id, channel_id, business_type, business_name, business_id,
              business_no, business_line_id, allocation_unit_id, round_no, phase, task_key,
              plan_instance_id, lock_effect_key, status, effect_result, path_member_ids_json,
              path_revision, evidence_revision, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOCK', ?, ?, ?, 'LOCKED', 'NONE', ?, ?, 0, 1)
            """,
            channelLockId,
            membership.channelId(),
            business.businessType(),
            business.businessName(),
            business.businessId(),
            business.businessNo(),
            business.businessLineId(),
            business.allocationUnitId(),
            business.roundNo(),
            business.taskKey(),
            business.planInstanceId(),
            AllocationKeys.derive(command.effectKey(), "CHANNEL:" + membership.channelId()),
            jsonMapper.writeValueAsString(pathMemberIds),
            channel.revision()
        );
    }

    private List<Long> insertAllocationLocks(
        WmsAllocationReserveCommand command,
        long decisionId,
        long candidateId,
        long storageId,
        Long channelLockId,
        WmsAllocationSnapshotInput input,
        List<LockRow> activeLocks
    ) {
        List<Long> ids = new ArrayList<>();
        AllocationDemand demand = command.request().demand();
        if (demand.capacityMode()) {
            for (Map.Entry<String, AllocationQuantity> entry : demand.quantities().entrySet()) {
                long lockId = nextId();
                ids.add(lockId);
                insertAllocationLock(
                    command, decisionId, candidateId, lockId, storageId, channelLockId,
                    "CAPACITY_SHARE", entry.getKey(), entry.getValue().unit(),
                    entry.getValue().amount(), null,
                    AllocationKeys.derive(command.effectKey(),
                        "CAPACITY:" + candidateId + ":" + entry.getKey())
                );
            }
            return ids;
        }
        Set<String> lockedSlots = activeLocks.stream()
            .filter(row -> "ENTITY_SLOT".equals(row.lockKind()))
            .map(LockRow::slotKey)
            .collect(Collectors.toSet());
        List<String> available = input.slotKeys().stream()
            .filter(key -> !lockedSlots.contains(key))
            .filter(key -> !input.occupiedSlotKeys().contains(key))
            .sorted()
            .limit(demand.slots())
            .toList();
        if (available.size() != demand.slots()) {
            throw invalid("实体槽余量在锁内变化: " + storageId);
        }
        for (String slotKey : available) {
            long lockId = nextId();
            ids.add(lockId);
            insertAllocationLock(
                command, decisionId, candidateId, lockId, storageId, channelLockId,
                "ENTITY_SLOT", null, null, null, slotKey,
                AllocationKeys.derive(command.effectKey(),
                    "SLOT:" + candidateId + ":" + slotKey)
            );
        }
        return ids;
    }

    private void insertAllocationLock(
        WmsAllocationReserveCommand command,
        long decisionId,
        long candidateId,
        long lockId,
        long storageId,
        Long channelLockId,
        String lockKind,
        String dimensionKey,
        String unit,
        BigDecimal lockedQty,
        String slotKey,
        String effectKey
    ) {
        WmsAllocationBusinessContext business = command.business();
        jdbcTemplate.update("""
            INSERT INTO wms_allocation_lock (
              allocation_lock_id, decision_id, candidate_id, channel_lock_id, business_type,
              business_name, business_id, business_no, business_line_id, allocation_unit_id,
              round_no, phase, task_key, plan_instance_id, storage_id, resource_domain,
              lock_kind, dimension_key, unit, locked_qty, slot_key, status, effect_result,
              effect_key, evidence_revision, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOCK', ?, ?, ?, ?, ?, ?, ?, ?, ?,
              'LOCKED', 'NONE', ?, 0, 1)
            """,
            lockId,
            decisionId,
            candidateId,
            channelLockId,
            business.businessType(),
            business.businessName(),
            business.businessId(),
            business.businessNo(),
            business.businessLineId(),
            business.allocationUnitId(),
            business.roundNo(),
            business.taskKey(),
            business.planInstanceId(),
            storageId,
            channelLockId == null ? "STORAGE" : "CHANNEL",
            lockKind,
            dimensionKey,
            unit,
            lockedQty,
            slotKey,
            effectKey
        );
    }

    private void updateDecisionResult(long decisionId, String result) {
        int rows = jdbcTemplate.update("""
            UPDATE wms_allocation_decision
            SET result = ?, revision = revision + 1
            WHERE decision_id = ? AND result = 'EVALUATED'
            """, result, decisionId);
        if (rows != 1) {
            throw new IllegalStateException("分配决策结果更新失败");
        }
    }

    private void replaceEvaluatedCandidates(long decisionId, AllocationPreview preview) {
        jdbcTemplate.update(
            "DELETE FROM wms_allocation_candidate WHERE decision_id = ?",
            decisionId
        );
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eligible", preview.candidates().size());
        summary.put("rejected", preview.rejections().size());
        summary.put("selectedStorageId", preview.selectedStorageId());
        summary.put("outcome", preview.outcome().name());
        int rows = jdbcTemplate.update("""
            UPDATE wms_allocation_decision
            SET candidate_summary_json = ?, result_reason = ?, revision = revision + 1
            WHERE decision_id = ? AND result = 'EVALUATED'
            """,
            jsonMapper.writeValueAsString(summary),
            preview.selectedStorageId() == null ? "无可用候选" : null,
            decisionId
        );
        if (rows != 1) {
            throw new IllegalStateException("分配决策候选刷新失败");
        }
    }

    private void verifyPersisted(
        WmsAllocationReserveCommand command,
        long decisionId,
        long selectedStorageId,
        Long channelLockId,
        List<Long> allocationLockIds
    ) {
        WmsAllocationDecisionRow decision = selectDecision(decisionId);
        if (decision == null || !"LOCKED".equals(decision.result())) {
            throw error(ERROR_VERIFY_FAILED, "分配决策未达到LOCKED", HttpStatus.HTTP_CONFLICT);
        }
        List<LockRow> locks = jdbcTemplate.query("""
            SELECT allocation_lock_id, storage_id, dimension_key, lock_kind, slot_key, locked_qty
            FROM wms_allocation_lock
            WHERE decision_id = ? AND status = 'LOCKED'
            ORDER BY allocation_lock_id
            """, (result, index) -> new LockRow(
            result.getLong("allocation_lock_id"),
            result.getLong("storage_id"),
            result.getString("dimension_key"),
            result.getString("lock_kind"),
            result.getString("slot_key"),
            result.getBigDecimal("locked_qty")
        ), decisionId);
        if (!locks.stream().map(LockRow::allocationLockId).toList().equals(allocationLockIds)
            || locks.stream().anyMatch(lock -> lock.storageId() != selectedStorageId)) {
            throw error(ERROR_VERIFY_FAILED, "分配锁回读与提交结果不一致",
                HttpStatus.HTTP_CONFLICT);
        }
        AllocationDemand demand = command.request().demand();
        if (demand.capacityMode()) {
            for (Map.Entry<String, AllocationQuantity> entry : demand.quantities().entrySet()) {
                BigDecimal total = locks.stream()
                    .filter(lock -> entry.getKey().equals(lock.dimensionKey()))
                    .map(LockRow::lockedQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (total.compareTo(entry.getValue().amount()) != 0) {
                    throw error(ERROR_VERIFY_FAILED,
                        "容量锁回读数量不一致: " + entry.getKey(), HttpStatus.HTTP_CONFLICT);
                }
            }
        } else if (locks.size() != demand.slots()) {
            throw error(ERROR_VERIFY_FAILED, "实体槽锁回读数量不一致", HttpStatus.HTTP_CONFLICT);
        }
        if (channelLockId != null) {
            Long active = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_access_channel_lock
                WHERE channel_lock_id = ? AND status = 'LOCKED'
                """, Long.class, channelLockId);
            if (active == null || active != 1L) {
                throw error(ERROR_VERIFY_FAILED, "通道锁回读不是活动状态",
                    HttpStatus.HTTP_CONFLICT);
            }
        }
    }

    private WmsAllocationReservationResult buildResult(WmsAllocationDecisionRow decision) {
        List<Long> channelLockIds = jdbcTemplate.queryForList("""
            SELECT DISTINCT channel_lock_id
            FROM wms_allocation_lock
            WHERE decision_id = ? AND status = 'LOCKED'
              AND channel_lock_id IS NOT NULL
            ORDER BY channel_lock_id
            """, Long.class, decision.decisionId());
        List<Long> allocationLockIds = jdbcTemplate.queryForList("""
            SELECT allocation_lock_id
            FROM wms_allocation_lock
            WHERE decision_id = ? AND status = 'LOCKED'
            ORDER BY allocation_lock_id
            """, Long.class, decision.decisionId());
        List<SelectedCandidateRow> selectedRows = jdbcTemplate.query("""
            SELECT candidate_id, storage_id
            FROM wms_allocation_candidate
            WHERE decision_id = ? AND selected_flag = 1
            """, (result, index) -> new SelectedCandidateRow(
            result.getLong("candidate_id"), result.getLong("storage_id")), decision.decisionId());
        Long selectedStorageId = selectedRows.isEmpty() ? null : selectedRows.getFirst().storageId();
        Long selectedCandidateId = selectedRows.isEmpty()
            ? null : selectedRows.getFirst().candidateId();
        if ("LOCKED".equals(decision.result())
            && (selectedStorageId == null || allocationLockIds.isEmpty())) {
            throw error(ERROR_VERIFY_FAILED, "分配决策声明LOCKED但缺少候选或锁",
                HttpStatus.HTTP_CONFLICT);
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", decision.result());
        checkpoint.put("decisionId", decision.decisionId());
        checkpoint.put("taskKey", decision.taskKey());
        checkpoint.put("planInstanceId", decision.planInstanceId());
        checkpoint.put("selectedStorageId", selectedStorageId);
        checkpoint.put("selectedCandidateId", selectedCandidateId);
        checkpoint.put("channelLockIds", channelLockIds);
        checkpoint.put("allocationLockIds", allocationLockIds);
        return new WmsAllocationReservationResult(
            decision.decisionId(),
            decision.result(),
            selectedStorageId,
            selectedCandidateId,
            channelLockIds,
            allocationLockIds,
            jsonMapper.writeValueAsString(checkpoint)
        );
    }

    private WmsAllocationReservationResult result(
        long decisionId,
        String result,
        Long selectedStorageId,
        Long selectedCandidateId,
        List<Long> channelLockIds,
        List<Long> allocationLockIds
    ) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", result);
        checkpoint.put("decisionId", decisionId);
        checkpoint.put("selectedStorageId", selectedStorageId);
        checkpoint.put("selectedCandidateId", selectedCandidateId);
        checkpoint.put("channelLockIds", channelLockIds);
        checkpoint.put("allocationLockIds", allocationLockIds);
        return new WmsAllocationReservationResult(
            decisionId,
            result,
            selectedStorageId,
            selectedCandidateId,
            channelLockIds,
            allocationLockIds,
            jsonMapper.writeValueAsString(checkpoint)
        );
    }

    private WmsAllocationDecisionRow selectDecisionByEffectKeyForUpdate(String effectKey) {
        List<WmsAllocationDecisionRow> rows = jdbcTemplate.query("""
            SELECT decision_id, business_type, business_id, business_line_id,
                   allocation_unit_id, round_no, task_key, plan_instance_id, input_hash, result
            FROM wms_allocation_decision
            WHERE effect_key = ?
            FOR UPDATE
            """, decisionMapper(), effectKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WmsAllocationDecisionRow selectDecisionByEffectKey(String effectKey) {
        List<WmsAllocationDecisionRow> rows = jdbcTemplate.query("""
            SELECT decision_id, business_type, business_id, business_line_id,
                   allocation_unit_id, round_no, task_key, plan_instance_id, input_hash, result
            FROM wms_allocation_decision
            WHERE effect_key = ?
            """, decisionMapper(), effectKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WmsAllocationDecisionRow selectDecision(long decisionId) {
        List<WmsAllocationDecisionRow> rows = jdbcTemplate.query("""
            SELECT decision_id, business_type, business_id, business_line_id,
                   allocation_unit_id, round_no, task_key, plan_instance_id, input_hash, result
            FROM wms_allocation_decision
            WHERE decision_id = ?
            """, decisionMapper(), decisionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private org.springframework.jdbc.core.RowMapper<WmsAllocationDecisionRow> decisionMapper() {
        return (result, index) -> new WmsAllocationDecisionRow(
            result.getLong("decision_id"),
            result.getString("business_type"),
            result.getLong("business_id"),
            result.getObject("business_line_id", Long.class),
            result.getLong("allocation_unit_id"),
            result.getInt("round_no"),
            result.getString("task_key"),
            result.getLong("plan_instance_id"),
            result.getString("input_hash"),
            result.getString("result")
        );
    }

    private long nextId() {
        Number generated = identifierGenerator.nextId(null);
        if (generated == null || generated.longValue() <= 0) {
            throw new IllegalStateException("ID生成器返回非法值");
        }
        return generated.longValue();
    }

    private BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale);
    }

    private BigDecimal scaleExact(BigDecimal value, int scale) {
        if (value == null) {
            throw invalid("实时账数量为空");
        }
        try {
            return value.setScale(scale);
        } catch (ArithmeticException error) {
            throw invalid("实时账数量精度超过容量声明: " + value);
        }
    }

    private ServiceException invalid(String message) {
        return new ServiceException(ERROR_INPUT_INVALID + ": " + message, HttpStatus.HTTP_BAD_REQUEST);
    }

    private ServiceException retryRequired(String message) {
        return new ServiceException(ERROR_LOCK_DOMAIN_CHANGED + ": " + message,
            HttpStatus.HTTP_CONFLICT);
    }

    private ServiceException error(String code, String message, int httpStatus) {
        return new ServiceException(code + ": " + message, httpStatus);
    }

    private void requirePositive(long value, String field) {
        if (value <= 0) {
            throw invalid(field + "必须为正整数");
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + "不能为空且长度不能超过" + maxLength);
        }
    }

    private void optionalText(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw invalid(field + "长度不能超过" + maxLength);
        }
    }

    private record StorageRow(
        long storageId,
        String storageCode,
        int status,
        int disableStatus,
        String delFlag,
        int version
    ) {
    }

    private record ChannelRow(long channelId, String status, int revision) {
    }

    private record CapacityRow(
        long capacityId,
        long storageId,
        String dimensionKey,
        BigDecimal capacityValue,
        String unit,
        int scale
    ) {
    }

    private record OccupancyRow(
        long occupancyId,
        long storageId,
        String dimensionKey,
        BigDecimal occupiedQty
    ) {
    }

    private record LockRow(
        long allocationLockId,
        long storageId,
        String dimensionKey,
        String lockKind,
        String slotKey,
        BigDecimal lockedQty
    ) {
    }

    private record SelectedCandidateRow(long candidateId, long storageId) {
    }

    private record WmsAllocationDecisionRow(
        long decisionId,
        String businessType,
        long businessId,
        Long businessLineId,
        long allocationUnitId,
        int roundNo,
        String taskKey,
        long planInstanceId,
        String inputHash,
        String result
    ) {
    }

    private static final class AllocationKeys {
        private static String derive(String effectKey, String part) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] value = digest.digest((effectKey + "|" + part)
                    .getBytes(StandardCharsets.UTF_8));
                StringBuilder builder = new StringBuilder("WMS:");
                for (byte item : value) {
                    builder.append(String.format("%02x", item));
                }
                return builder.toString();
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("JVM缺少SHA-256", error);
            }
        }

        private AllocationKeys() {
        }
    }
}
