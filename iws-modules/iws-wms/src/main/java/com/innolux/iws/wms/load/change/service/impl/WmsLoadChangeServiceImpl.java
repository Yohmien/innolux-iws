/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆事实
 * @Version      : V1.0
 * @Description  : 锁内重读装载森林与容量账，原子提交关系、根位置、占用和历史；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.reservation.space.WmsAllocationSpaceReader;
import com.innolux.iws.wms.allocation.reservation.space.WmsAllocationSpaceReader.Membership;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCommand;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeCheckResult;
import com.innolux.iws.wms.load.change.domain.WmsLoadChangeResult;
import com.innolux.iws.wms.load.change.domain.WmsLoadMutation;
import com.innolux.iws.wms.load.change.service.WmsLoadChangeService;
import com.innolux.iws.wms.load.kernel.LoadEdge;
import com.innolux.iws.wms.load.kernel.LoadKernelException;
import com.innolux.iws.wms.load.kernel.LoadNode;
import com.innolux.iws.wms.load.kernel.LoadTree;
import com.innolux.iws.wms.load.kernel.LoadTreeKernel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 装拆与顶层槽容量同事务实现。
 *
 * <p>当前基础合同只接受 {@code TOP_LEVEL_LOAD} 容量维度；其他物理容量维度尚无可信
 * 测量或转换数据，出现即失败关闭。本储位本地容量锁的预留份额计入变更后的容量账，
 * 但不改写锁终态；实体槽锁、活动通道锁和已推进外部效果的锁仍失败关闭。实现先锁定
 * 全部装载节点、关系与根位置，避免全树循环校验和并发关系变化之间出现窗口，再按通道、
 * 储位、容量、占用和活动锁顺序复核。</p>
 */
@Service
public class WmsLoadChangeServiceImpl implements WmsLoadChangeService {

    private static final Pattern EFFECT_KEY_PATTERN = Pattern.compile("^WMS:[0-9a-f]{64}$");

    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private static final String DIMENSION_TOP_LEVEL_LOAD = "TOP_LEVEL_LOAD";

    private static final String UNIT_LOAD = "LOAD";

    private static final String LOCATION_STORAGE = "STORAGE";

    private static final Set<String> LOCATION_KINDS =
        Set.of("STORAGE", "OUTSIDE", "IN_TRANSIT", "UNKNOWN");

    private static final int TEXT_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final WmsAllocationSpaceReader spaceReader;

    private final IdentifierGenerator identifierGenerator;

    public WmsLoadChangeServiceImpl(
        JdbcTemplate jdbcTemplate,
        WmsAllocationSpaceReader spaceReader,
        IdentifierGenerator identifierGenerator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.spaceReader = spaceReader;
        this.identifierGenerator = identifierGenerator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsLoadChangeResult apply(WmsLoadChangeCommand command) {
        validate(command);
        LoadGraph graph = readGraph(true);
        OccupancyEffectRow existing = selectEffectForUpdate(command.effectKey());
        if (existing != null) {
            verifyEffect(command, existing);
            return replay(existing);
        }
        LoadTree beforeTree = parseTree(graph.nodes(), toEdges(graph.parentByChild()));
        Map<Long, Long> parentByChild = new LinkedHashMap<>(graph.parentByChild());
        Map<Long, LocationRow> locations = new LinkedHashMap<>(graph.locationsByRoot());
        Set<Long> changedChildren = new HashSet<>();

        for (WmsLoadMutation mutation : command.mutations()) {
            NodeRow child = graph.nodesById().get(mutation.childNodeId());
            if (child == null || !changedChildren.add(mutation.childNodeId())) {
                throw invalid("装载子节点不存在或重复: " + mutation.childNodeId());
            }
            if (child.revision() != mutation.expectedNodeRevision()) {
                throw conflict(ERROR_RELATION_CONFLICT,
                    "节点修订号不一致: " + mutation.childNodeId());
            }
            Long currentParent = parentByChild.get(mutation.childNodeId());
            if (!Objects.equals(currentParent, mutation.expectedParentNodeId())) {
                throw conflict(ERROR_RELATION_CONFLICT,
                    "当前父节点与冻结输入不一致: " + mutation.childNodeId());
            }
            if (mutation.targetParentNodeId() == null) {
                validateRootLocation(mutation);
                parentByChild.remove(mutation.childNodeId());
                locations.put(mutation.childNodeId(), new LocationRow(
                    0L,
                    mutation.childNodeId(),
                    mutation.targetLocationKind(),
                    mutation.targetStorageId(),
                    0
                ));
            } else {
                NodeRow parent = graph.nodesById().get(mutation.targetParentNodeId());
                if (parent == null || parent.nodeType() == LoadNode.Type.MATERIAL) {
                    throw invalid("目标父节点不存在或不是载具: " + mutation.targetParentNodeId());
                }
                parentByChild.put(mutation.childNodeId(), mutation.targetParentNodeId());
                locations.remove(mutation.childNodeId());
            }
        }

        LoadTree afterTree = parseTree(graph.nodes(), toEdges(parentByChild));
        assertLocationsOnlyOnRoots(afterTree, locations);
        Set<Long> impactedStorageIds = impactedStorageIds(
            beforeTree, afterTree, locations, graph.locationsByRoot(), changedChildren);

        Map<Long, Membership> initialMemberships = spaceReader.readInitial(impactedStorageIds);
        List<Long> channelIds = sortedChannelIds(initialMemberships);
        lockChannels(channelIds);
        Map<Long, StorageRow> storages = lockStorages(impactedStorageIds);
        Map<Long, Membership> currentMemberships =
            spaceReader.readCurrentForUpdate(impactedStorageIds);
        assertMembershipUnchanged(initialMemberships, currentMemberships);
        Map<Long, List<CapacityRow>> capacities = lockCapacities(impactedStorageIds);
        List<OccupancyRow> occupancyRows = lockOccupancies(impactedStorageIds);
        Map<Long, BigDecimal> reservedByStorage = accountActiveAllocationLocks(
            lockAllocationLocks(impactedStorageIds, true));
        // 与 RESERVE_STORAGE 保持容量 -> 占用 -> allocation_lock -> channel_lock 的锁序。
        rejectActiveChannels(lockActiveChannels(channelIds));

        validateDestinationStorages(command, storages);
        validateCapacity(
            impactedStorageIds, beforeTree, afterTree,
            graph.locationsByRoot(), locations, capacities, occupancyRows,
            reservedByStorage);

        Map<OccupancyKey, OccupancyRow> activeByKey = occupancyRows.stream()
            .filter(row -> "ACTIVE".equals(row.status()))
            .collect(Collectors.toMap(
                row -> new OccupancyKey(row.storageId(), row.rootNodeId()),
                Function.identity(),
                (left, right) -> {
                    throw ledgerDrift("同一根节点存在重复活动顶层槽占用");
                },
                LinkedHashMap::new
            ));
        Set<OccupancyKey> beforeKeys =
            occupancyKeys(beforeTree, graph.locationsByRoot(), impactedStorageIds);
        Set<OccupancyKey> afterKeys =
            occupancyKeys(afterTree, locations, impactedStorageIds);
        if (!activeByKey.keySet().equals(beforeKeys)) {
            throw ledgerDrift("活动占用明细与当前根节点位置不一致");
        }

        List<OccupancyRow> closing = activeByKey.values().stream()
            .filter(row -> !afterKeys.contains(new OccupancyKey(row.storageId(), row.rootNodeId())))
            .sorted(Comparator.comparingLong(OccupancyRow::occupancyId))
            .toList();
        List<OccupancyKey> creating = afterKeys.stream()
            .filter(key -> !beforeKeys.contains(key))
            .sorted()
            .toList();
        Map<OccupancyKey, Integer> nextGenerations = nextGenerations(occupancyRows);

        long effectId = nextId();
        insertEffect(command, effectId);
        List<Long> historyIds = new ArrayList<>();
        for (WmsLoadMutation mutation : command.mutations()) {
            applyMutation(command, graph, mutation, locations, historyIds);
        }
        for (OccupancyRow row : closing) {
            closeOccupancy(command, row);
        }
        List<Long> createdOccupancyIds = new ArrayList<>();
        for (OccupancyKey key : creating) {
            long occupancyId = nextId();
            int generation = nextGenerations.getOrDefault(key, 0) + 1;
            insertOccupancy(command, effectId, occupancyId, key, generation);
            createdOccupancyIds.add(occupancyId);
        }

        WmsLoadChangeResult result = result(
            effectId, command.effectKey(), historyIds,
            closing.stream().map(OccupancyRow::occupancyId).toList(),
            afterTree, locations
        );
        verifyPersisted(result, impactedStorageIds);
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true, rollbackFor = Exception.class)
    public WmsLoadChangeCheckResult check(WmsLoadChangeCommand command) {
        validate(command);
        LoadGraph graph = readGraph(false);
        LoadTree beforeTree = parseTree(graph.nodes(), toEdges(graph.parentByChild()));
        Map<Long, Long> parentByChild = new LinkedHashMap<>(graph.parentByChild());
        Map<Long, LocationRow> locations = new LinkedHashMap<>(graph.locationsByRoot());
        Set<Long> changedChildren = new HashSet<>();

        for (WmsLoadMutation mutation : command.mutations()) {
            NodeRow child = graph.nodesById().get(mutation.childNodeId());
            if (child == null || !changedChildren.add(mutation.childNodeId())) {
                throw invalid("装载子节点不存在或重复: " + mutation.childNodeId());
            }
            if (child.revision() != mutation.expectedNodeRevision()) {
                throw conflict(ERROR_RELATION_CONFLICT,
                    "节点修订号不一致: " + mutation.childNodeId());
            }
            Long currentParent = parentByChild.get(mutation.childNodeId());
            if (!Objects.equals(currentParent, mutation.expectedParentNodeId())) {
                throw conflict(ERROR_RELATION_CONFLICT,
                    "当前父节点与冻结输入不一致: " + mutation.childNodeId());
            }
            if (mutation.targetParentNodeId() == null) {
                validateRootLocation(mutation);
                parentByChild.remove(mutation.childNodeId());
                locations.put(mutation.childNodeId(), new LocationRow(
                    0L,
                    mutation.childNodeId(),
                    mutation.targetLocationKind(),
                    mutation.targetStorageId(),
                    0
                ));
            } else {
                NodeRow parent = graph.nodesById().get(mutation.targetParentNodeId());
                if (parent == null || parent.nodeType() == LoadNode.Type.MATERIAL) {
                    throw invalid("目标父节点不存在或不是载具: " + mutation.targetParentNodeId());
                }
                parentByChild.put(mutation.childNodeId(), mutation.targetParentNodeId());
                locations.remove(mutation.childNodeId());
            }
        }

        LoadTree afterTree = parseTree(graph.nodes(), toEdges(parentByChild));
        assertLocationsOnlyOnRoots(afterTree, locations);
        Set<Long> impacted = impactedStorageIds(
            beforeTree, afterTree, locations, graph.locationsByRoot(), changedChildren);
        List<WmsLoadChangeCheckResult.NodeChange> changes = command.mutations().stream()
            .sorted(Comparator.comparingLong(WmsLoadMutation::childNodeId))
            .map(mutation -> new WmsLoadChangeCheckResult.NodeChange(
                mutation.childNodeId(),
                mutation.expectedParentNodeId(),
                mutation.targetParentNodeId(),
                beforeTree.rootIdOf(mutation.childNodeId()),
                afterTree.rootIdOf(mutation.childNodeId())
            ))
            .toList();
        return new WmsLoadChangeCheckResult(
            command.effectKey(),
            command.inputHash(),
            changes,
            beforeTree.rootIds(),
            afterTree.rootIds(),
            impacted.stream().sorted().toList()
        );
    }

    private LoadGraph readGraph(boolean forUpdate) {
        String nodesSql = """
            SELECT load_node_id, node_type, inventory_id, container_id, revision
            FROM wms_load_node
            ORDER BY load_node_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<NodeRow> nodes = jdbcTemplate.query(nodesSql, (result, index) -> new NodeRow(
            result.getLong("load_node_id"),
            LoadNode.Type.valueOf(result.getString("node_type")),
            nullableLong(result, "inventory_id"),
            nullableLong(result, "container_id"),
            result.getInt("revision")
        ));
        String relationsSql = """
            SELECT relation_id, child_node_id, parent_node_id, revision
            FROM wms_load_relation
            ORDER BY child_node_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<RelationRow> relations = jdbcTemplate.query(relationsSql, (result, index) -> new RelationRow(
            result.getLong("relation_id"),
            result.getLong("child_node_id"),
            result.getLong("parent_node_id"),
            result.getInt("revision")
        ));
        String locationsSql = """
            SELECT storage_info_id, root_node_id, location_kind, storage_id, revision
            FROM wms_storage_info
            ORDER BY root_node_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<LocationRow> locations = jdbcTemplate.query(locationsSql, (result, index) -> new LocationRow(
            result.getLong("storage_info_id"),
            result.getLong("root_node_id"),
            result.getString("location_kind"),
            nullableLong(result, "storage_id"),
            result.getInt("revision")
        ));
        Map<Long, NodeRow> nodesById = nodes.stream().collect(Collectors.toMap(
            NodeRow::nodeId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Long, RelationRow> relationsByChild = relations.stream().collect(Collectors.toMap(
            RelationRow::childNodeId, Function.identity(),
            (left, right) -> left, LinkedHashMap::new));
        Map<Long, LocationRow> locationsByRoot = locations.stream().collect(Collectors.toMap(
            LocationRow::rootNodeId, Function.identity(),
            (left, right) -> left, LinkedHashMap::new));
        Map<Long, Long> parentByChild = relations.stream().collect(Collectors.toMap(
            RelationRow::childNodeId, RelationRow::parentNodeId,
            (left, right) -> left, LinkedHashMap::new));
        return new LoadGraph(
            nodes, relationsByChild, locationsByRoot, nodesById, parentByChild);
    }

    private LoadTree parseTree(List<NodeRow> rows, List<LoadEdge> edges) {
        List<LoadNode> nodes = rows.stream()
            .map(row -> new LoadNode(
                row.nodeId(), row.nodeType(), row.inventoryId(), row.containerId()))
            .toList();
        try {
            return LoadTreeKernel.parse(nodes, edges);
        } catch (LoadKernelException error) {
            throw invalid("装拆后装载树不合法: " + error.getMessage());
        }
    }

    private List<LoadEdge> toEdges(Map<Long, Long> parentByChild) {
        return parentByChild.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new LoadEdge(entry.getKey(), entry.getValue()))
            .toList();
    }

    private void assertLocationsOnlyOnRoots(LoadTree tree, Map<Long, LocationRow> locations) {
        Set<Long> roots = new HashSet<>(tree.rootIds());
        if (locations.keySet().stream().anyMatch(rootId -> !roots.contains(rootId))) {
            throw invalid("非根装载节点不能保存根位置");
        }
    }

    private Set<Long> impactedStorageIds(
        LoadTree beforeTree,
        LoadTree afterTree,
        Map<Long, LocationRow> afterLocations,
        Map<Long, LocationRow> beforeLocations,
        Collection<Long> changedChildren
    ) {
        Set<Long> storageIds = new LinkedHashSet<>();
        for (Long childId : changedChildren) {
            addStorage(storageIds, beforeLocations.get(beforeTree.rootIdOf(childId)));
            addStorage(storageIds, afterLocations.get(afterTree.rootIdOf(childId)));
        }
        return storageIds;
    }

    private void addStorage(Set<Long> storageIds, LocationRow location) {
        if (location != null && LOCATION_STORAGE.equals(location.locationKind())) {
            storageIds.add(location.storageId());
        }
    }

    private List<Long> sortedChannelIds(Map<Long, Membership> memberships) {
        return memberships.values().stream()
            .map(Membership::channelId)
            .distinct()
            .sorted()
            .toList();
    }

    private Map<Long, ChannelRow> lockChannels(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        List<ChannelRow> rows = namedJdbc.query("""
            SELECT channel_id, status, revision
            FROM wms_access_channel
            WHERE channel_id IN (:channelIds)
            ORDER BY channel_id
            FOR UPDATE
            """, Map.of("channelIds", channelIds), (result, index) -> new ChannelRow(
            result.getLong("channel_id"),
            result.getString("status"),
            result.getInt("revision")
        ));
        if (rows.size() != channelIds.size()) {
            throw conflict(ERROR_RELATION_CONFLICT, "通道在锁前被删除或替换");
        }
        return rows.stream().collect(Collectors.toMap(
            ChannelRow::channelId, Function.identity(),
            (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, StorageRow> lockStorages(Set<Long> storageIds) {
        if (storageIds.isEmpty()) {
            return Map.of();
        }
        List<StorageRow> rows = namedJdbc.query("""
            SELECT storage_id, storage_code, status, lock_status, disable_status, del_flag, version
            FROM wms_storage
            WHERE storage_id IN (:storageIds)
            ORDER BY storage_id
            FOR UPDATE
            """, Map.of("storageIds", storageIds), (result, index) -> new StorageRow(
            result.getLong("storage_id"),
            result.getString("storage_code"),
            result.getInt("status"),
            result.getInt("lock_status"),
            result.getInt("disable_status"),
            result.getString("del_flag"),
            result.getInt("version")
        ));
        if (rows.size() != storageIds.size()) {
            throw invalid("装拆目标储位不存在");
        }
        return rows.stream().collect(Collectors.toMap(
            StorageRow::storageId, Function.identity(),
            (left, right) -> left, LinkedHashMap::new));
    }

    private void assertMembershipUnchanged(
        Map<Long, Membership> initial,
        Map<Long, Membership> current
    ) {
        Set<Long> storageIds = new LinkedHashSet<>(initial.keySet());
        storageIds.addAll(current.keySet());
        for (Long storageId : storageIds) {
            Membership before = initial.get(storageId);
            Membership after = current.get(storageId);
            if (!sameMembership(before, after)) {
                throw conflict(ERROR_RELATION_CONFLICT,
                    "储位通道成员或锁域在读取后变化: " + storageId);
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

    private Set<Long> lockActiveChannels(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(namedJdbc.queryForList("""
            SELECT channel_id
            FROM wms_access_channel_lock
            WHERE status = 'LOCKED' AND channel_id IN (:channelIds)
            ORDER BY channel_id
            FOR UPDATE
            """, Map.of("channelIds", channelIds), Long.class));
    }

    private void rejectActiveChannels(Set<Long> activeChannels) {
        if (!activeChannels.isEmpty()) {
            throw conflict(ERROR_ALLOCATION_UNSUPPORTED,
                "装拆储位存在活动通道锁，未接锁消费协议: " + activeChannels);
        }
    }

    private List<AllocationLockRow> lockAllocationLocks(
        Set<Long> storageIds,
        boolean forUpdate
    ) {
        if (storageIds.isEmpty()) {
            return List.of();
        }
        String sql = """
            SELECT allocation_lock_id, storage_id, resource_domain, lock_kind,
                   dimension_key, unit, locked_qty, effect_result
            FROM wms_allocation_lock
            WHERE status = 'LOCKED' AND storage_id IN (:storageIds)
            ORDER BY allocation_lock_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        return namedJdbc.query(sql, Map.of("storageIds", storageIds),
            (result, index) -> new AllocationLockRow(
                result.getLong("allocation_lock_id"),
                result.getLong("storage_id"),
                result.getString("resource_domain"),
                result.getString("lock_kind"),
                result.getString("dimension_key"),
                result.getString("unit"),
                result.getBigDecimal("locked_qty"),
                result.getString("effect_result")
            ));
    }

    private Map<Long, BigDecimal> accountActiveAllocationLocks(
        List<AllocationLockRow> locks
    ) {
        Map<Long, BigDecimal> reservedByStorage = new LinkedHashMap<>();
        for (AllocationLockRow lock : locks) {
            if (!"STORAGE".equals(lock.resourceDomain())) {
                throw conflict(ERROR_ALLOCATION_UNSUPPORTED,
                    "装拆储位存在活动通道分配锁: " + lock.allocationLockId());
            }
            if (!"CAPACITY_SHARE".equals(lock.lockKind())) {
                throw conflict(ERROR_ALLOCATION_UNSUPPORTED,
                    "装拆储位存在实体槽分配锁: " + lock.allocationLockId());
            }
            if (!"NONE".equals(lock.effectResult())) {
                throw conflict(ERROR_ALLOCATION_UNSUPPORTED,
                    "装拆储位分配锁已推进外部效果: " + lock.allocationLockId());
            }
            if (!DIMENSION_TOP_LEVEL_LOAD.equals(lock.dimensionKey())
                || !UNIT_LOAD.equals(lock.unit())
                || lock.lockedQty() == null
                || lock.lockedQty().signum() <= 0
                || lock.lockedQty().stripTrailingZeros().scale() > 0) {
                throw conflict(ERROR_CAPACITY_UNSUPPORTED,
                    "装拆储位分配锁容量维度或数量不支持: "
                        + lock.allocationLockId());
            }
            reservedByStorage.merge(
                lock.storageId(), lock.lockedQty(), BigDecimal::add);
        }
        return reservedByStorage;
    }

    private Map<Long, List<CapacityRow>> lockCapacities(Set<Long> storageIds) {
        if (storageIds.isEmpty()) {
            return Map.of();
        }
        List<CapacityRow> rows = namedJdbc.query("""
            SELECT capacity_id, storage_id, dimension_key, capacity_value, unit, scale
            FROM wms_storage_capacity
            WHERE active_flag = 1 AND storage_id IN (:storageIds)
            ORDER BY storage_id, dimension_key
            FOR UPDATE
            """, Map.of("storageIds", storageIds), (result, index) -> new CapacityRow(
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

    private List<OccupancyRow> lockOccupancies(Set<Long> storageIds) {
        if (storageIds.isEmpty()) {
            return List.of();
        }
        return namedJdbc.query("""
            SELECT occupancy_id, occupancy_effect_id, storage_id, root_node_id,
                   dimension_key, occupied_qty, unit, generation, status
            FROM wms_storage_occupancy
            WHERE storage_id IN (:storageIds)
            ORDER BY storage_id, root_node_id, occupancy_id
            FOR UPDATE
            """, Map.of("storageIds", storageIds), (result, index) -> new OccupancyRow(
            result.getLong("occupancy_id"),
            result.getLong("occupancy_effect_id"),
            result.getLong("storage_id"),
            result.getLong("root_node_id"),
            result.getString("dimension_key"),
            result.getBigDecimal("occupied_qty"),
            result.getString("unit"),
            result.getInt("generation"),
            result.getString("status")
        ));
    }

    private void validateDestinationStorages(
        WmsLoadChangeCommand command,
        Map<Long, StorageRow> storages
    ) {
        for (WmsLoadMutation mutation : command.mutations()) {
            if (mutation.targetParentNodeId() != null
                || !LOCATION_STORAGE.equals(mutation.targetLocationKind())) {
                continue;
            }
            StorageRow storage = storages.get(mutation.targetStorageId());
            if (storage == null || !"0".equals(storage.delFlag())
                || storage.disableStatus() != 0 || storage.lockStatus() != 0
                || storage.status() == 2 || storage.status() == 3) {
                throw conflict(ERROR_INVALID_COMMAND,
                    "目标储位不可用于装拆: " + mutation.targetStorageId());
            }
        }
    }

    private void validateCapacity(
        Set<Long> impactedStorageIds,
        LoadTree beforeTree,
        LoadTree afterTree,
        Map<Long, LocationRow> beforeLocations,
        Map<Long, LocationRow> afterLocations,
        Map<Long, List<CapacityRow>> capacities,
        List<OccupancyRow> occupancyRows,
        Map<Long, BigDecimal> reservedByStorage
    ) {
        Map<Long, List<OccupancyRow>> activeByStorage = occupancyRows.stream()
            .filter(row -> "ACTIVE".equals(row.status()))
            .peek(row -> {
                if (!DIMENSION_TOP_LEVEL_LOAD.equals(row.dimensionKey())
                    || !UNIT_LOAD.equals(row.unit())
                    || row.occupiedQty().compareTo(BigDecimal.ONE) != 0) {
                    throw conflict(ERROR_CAPACITY_UNSUPPORTED,
                        "活动占用含未支持容量维度或数量: " + row.occupancyId());
                }
            })
            .collect(Collectors.groupingBy(
                OccupancyRow::storageId, LinkedHashMap::new, Collectors.toList()));
        for (Long storageId : impactedStorageIds) {
            List<CapacityRow> storageCapacities =
                capacities.getOrDefault(storageId, List.of());
            if (storageCapacities.stream()
                .anyMatch(row -> !DIMENSION_TOP_LEVEL_LOAD.equals(row.dimensionKey()))) {
                throw conflict(ERROR_CAPACITY_UNSUPPORTED,
                    "储位配置含未支持容量维度: " + storageId);
            }
            int afterRootCount = countRootsAtStorage(afterTree, afterLocations, storageId);
            int beforeRootCount = countRootsAtStorage(beforeTree, beforeLocations, storageId);
            BigDecimal reserved = reservedByStorage.getOrDefault(
                storageId, BigDecimal.ZERO);
            if (afterRootCount == 0 && beforeRootCount == 0
                && reserved.signum() == 0
                && activeByStorage.getOrDefault(storageId, List.of()).isEmpty()) {
                continue;
            }
            CapacityRow topLevel = storageCapacities.stream()
                .filter(row -> DIMENSION_TOP_LEVEL_LOAD.equals(row.dimensionKey()))
                .findFirst()
                .orElseThrow(() -> conflict(ERROR_CAPACITY_MISSING,
                    "储位缺少活动TOP_LEVEL_LOAD容量: " + storageId));
            if (topLevel.scale() != 0 || !UNIT_LOAD.equals(topLevel.unit())) {
                throw conflict(ERROR_CAPACITY_UNSUPPORTED,
                    "TOP_LEVEL_LOAD容量单位或精度不支持: " + storageId);
            }
            BigDecimal afterCommittedLoads =
                BigDecimal.valueOf(afterRootCount).add(reserved);
            if (afterCommittedLoads.compareTo(topLevel.capacityValue()) > 0) {
                throw conflict(ERROR_CAPACITY_EXCEEDED,
                    "储位顶层槽容量不足（含活动分配锁预留）: " + storageId);
            }
        }
    }

    private int countRootsAtStorage(
        LoadTree tree,
        Map<Long, LocationRow> locations,
        long storageId
    ) {
        return (int) tree.rootIds().stream()
            .map(locations::get)
            .filter(Objects::nonNull)
            .filter(location -> LOCATION_STORAGE.equals(location.locationKind()))
            .filter(location -> Objects.equals(location.storageId(), storageId))
            .count();
    }

    private Set<OccupancyKey> occupancyKeys(
        LoadTree tree,
        Map<Long, LocationRow> locations,
        Set<Long> storageIds
    ) {
        return tree.rootIds().stream()
            .map(rootId -> {
                LocationRow location = locations.get(rootId);
                if (location == null || !LOCATION_STORAGE.equals(location.locationKind())) {
                    return null;
                }
                if (!storageIds.contains(location.storageId())) {
                    return null;
                }
                return new OccupancyKey(location.storageId(), rootId);
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<OccupancyKey, Integer> nextGenerations(List<OccupancyRow> rows) {
        Map<OccupancyKey, Integer> generations = new HashMap<>();
        for (OccupancyRow row : rows) {
            if (!DIMENSION_TOP_LEVEL_LOAD.equals(row.dimensionKey())
                || !UNIT_LOAD.equals(row.unit())) {
                continue;
            }
            OccupancyKey key = new OccupancyKey(row.storageId(), row.rootNodeId());
            generations.merge(key, row.generation(), Math::max);
        }
        return generations;
    }

    private void applyMutation(
        WmsLoadChangeCommand command,
        LoadGraph graph,
        WmsLoadMutation mutation,
        Map<Long, LocationRow> locations,
        List<Long> historyIds
    ) {
        RelationRow relation = graph.relationsByChild().get(mutation.childNodeId());
        int relationRevision = relation == null ? 1 : relation.revision() + 1;
        if (mutation.targetParentNodeId() == null) {
            if (relation != null) {
                jdbcTemplate.update(
                    "DELETE FROM wms_load_relation WHERE child_node_id = ?",
                    mutation.childNodeId()
                );
            }
            upsertRootLocation(command, graph, mutation, locations);
        } else {
            if (relation == null) {
                jdbcTemplate.update("""
                    INSERT INTO wms_load_relation (
                      relation_id, child_node_id, parent_node_id, revision,
                      create_dept, create_by
                    ) VALUES (?, ?, ?, 1, ?, ?)
                    """,
                    nextId(), mutation.childNodeId(), mutation.targetParentNodeId(),
                    command.departmentId(), command.actorId()
                );
            } else {
                int rows = jdbcTemplate.update("""
                    UPDATE wms_load_relation
                    SET parent_node_id = ?, revision = ?, update_by = ?, update_time = ?
                    WHERE relation_id = ? AND revision = ?
                    """,
                    mutation.targetParentNodeId(), relationRevision,
                    command.actorId(), command.occurredAt(),
                    relation.relationId(), relation.revision()
                );
                if (rows != 1) {
                    throw conflict(ERROR_RELATION_CONFLICT, "装载关系并发修订冲突");
                }
            }
            LocationRow existingLocation = graph.locationsByRoot().get(mutation.childNodeId());
            if (existingLocation != null) {
                jdbcTemplate.update(
                    "DELETE FROM wms_storage_info WHERE root_node_id = ?",
                    mutation.childNodeId()
                );
            }
        }
        int nodeRows = jdbcTemplate.update("""
            UPDATE wms_load_node
            SET revision = revision + 1, update_by = ?, update_time = ?
            WHERE load_node_id = ? AND revision = ?
            """,
            command.actorId(), command.occurredAt(),
            mutation.childNodeId(), mutation.expectedNodeRevision()
        );
        if (nodeRows != 1) {
            throw conflict(ERROR_RELATION_CONFLICT, "装载节点并发修订冲突");
        }
        long historyId = nextId();
        insertRelationHistory(
            command, historyId, mutation, relationRevision);
        historyIds.add(historyId);
    }

    private void upsertRootLocation(
        WmsLoadChangeCommand command,
        LoadGraph graph,
        WmsLoadMutation mutation,
        Map<Long, LocationRow> locations
    ) {
        LocationRow existing = graph.locationsByRoot().get(mutation.childNodeId());
        if (existing == null) {
            jdbcTemplate.update("""
                INSERT INTO wms_storage_info (
                  storage_info_id, root_node_id, location_kind, storage_id, revision,
                  create_dept, create_by
                ) VALUES (?, ?, ?, ?, 1, ?, ?)
                """,
                nextId(), mutation.childNodeId(), mutation.targetLocationKind(),
                mutation.targetStorageId(), command.departmentId(), command.actorId()
            );
            return;
        }
        int rows = jdbcTemplate.update("""
            UPDATE wms_storage_info
            SET location_kind = ?, storage_id = ?, revision = revision + 1,
                update_by = ?, update_time = ?
            WHERE storage_info_id = ? AND revision = ?
            """,
            mutation.targetLocationKind(), mutation.targetStorageId(),
            command.actorId(), command.occurredAt(),
            existing.storageInfoId(), existing.revision()
        );
        if (rows != 1) {
            throw conflict(ERROR_RELATION_CONFLICT, "根节点位置并发修订冲突");
        }
    }

    private void insertRelationHistory(
        WmsLoadChangeCommand command,
        long historyId,
        WmsLoadMutation mutation,
        int relationRevision
    ) {
        jdbcTemplate.update("""
            INSERT INTO wms_load_relation_history (
              relation_history_id, child_node_id, from_parent_node_id, to_parent_node_id,
              relation_revision, effect_key, business_type, business_id, business_line_id,
              task_key, plan_instance_id, event_id, attempt_id, operator_id,
              occurred_at, create_dept, create_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            historyId,
            mutation.childNodeId(),
            mutation.expectedParentNodeId(),
            mutation.targetParentNodeId(),
            relationRevision,
            command.effectKey(),
            command.businessType(),
            command.businessId(),
            command.businessLineId(),
            command.taskKey(),
            command.planInstanceId(),
            command.eventId(),
            command.attemptId(),
            command.actorId(),
            command.occurredAt(),
            command.departmentId(),
            command.actorId()
        );
    }

    private void insertEffect(WmsLoadChangeCommand command, long effectId) {
        jdbcTemplate.update("""
            INSERT INTO wms_occupancy_effect (
              occupancy_effect_id, effect_key, business_type, business_id, business_line_id,
              task_key, plan_instance_id, event_id, attempt_id, trace_id, effect_result,
              reason, evidence_ref, occurred_at, create_dept, create_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?, ?)
            """,
            effectId,
            command.effectKey(),
            command.businessType(),
            command.businessId(),
            command.businessLineId(),
            command.taskKey(),
            command.planInstanceId(),
            command.eventId(),
            command.attemptId(),
            command.traceId(),
            command.reason(),
            command.evidenceRef(),
            command.occurredAt(),
            command.departmentId(),
            command.actorId()
        );
    }

    private void closeOccupancy(WmsLoadChangeCommand command, OccupancyRow row) {
        int rows = jdbcTemplate.update("""
            UPDATE wms_storage_occupancy
            SET status = 'CLOSED', revision = revision + 1,
                update_by = ?, update_time = ?
            WHERE occupancy_id = ? AND status = 'ACTIVE'
            """,
            command.actorId(), command.occurredAt(), row.occupancyId()
        );
        if (rows != 1) {
            throw ledgerDrift("关闭活动占用时状态已变化: " + row.occupancyId());
        }
    }

    private void insertOccupancy(
        WmsLoadChangeCommand command,
        long effectId,
        long occupancyId,
        OccupancyKey key,
        int generation
    ) {
        jdbcTemplate.update("""
            INSERT INTO wms_storage_occupancy (
              occupancy_id, occupancy_effect_id, storage_id, root_node_id, dimension_key,
              occupied_qty, unit, generation, status, revision, create_dept, create_by
            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, 'ACTIVE', 1, ?, ?)
            """,
            occupancyId,
            effectId,
            key.storageId(),
            key.rootNodeId(),
            DIMENSION_TOP_LEVEL_LOAD,
            UNIT_LOAD,
            generation,
            command.departmentId(),
            command.actorId()
        );
    }

    private WmsLoadChangeResult result(
        long effectId,
        String effectKey,
        List<Long> historyIds,
        List<Long> closedOccupancyIds,
        LoadTree tree,
        Map<Long, LocationRow> locations
    ) {
        List<Long> activeIds = jdbcTemplate.queryForList("""
            SELECT occupancy_id
            FROM wms_storage_occupancy
            WHERE occupancy_effect_id = ? AND status = 'ACTIVE'
            ORDER BY occupancy_id
            """, Long.class, effectId);
        Map<Long, Integer> counts = new TreeMap<>();
        for (Long rootId : tree.rootIds()) {
            LocationRow location = locations.get(rootId);
            if (location != null && LOCATION_STORAGE.equals(location.locationKind())) {
                counts.merge(location.storageId(), 1, Integer::sum);
            }
        }
        return new WmsLoadChangeResult(
            effectId,
            effectKey,
            historyIds,
            activeIds,
            closedOccupancyIds,
            tree.rootIds(),
            counts
        );
    }

    private void verifyPersisted(WmsLoadChangeResult result, Set<Long> impactedStorageIds) {
        long historyCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM wms_load_relation_history WHERE effect_key = ?
            """, Long.class, result.effectKey());
        if (historyCount != result.relationHistoryIds().size()) {
            throw new IllegalStateException("装拆关系历史回读数量不一致");
        }
        if (!impactedStorageIds.isEmpty()) {
            Long activeCount = namedJdbc.queryForObject("""
                SELECT COUNT(*) FROM wms_storage_occupancy
                WHERE status = 'ACTIVE' AND dimension_key = :dimension AND unit = :unit
                  AND storage_id IN (:storageIds)
                """, Map.of(
                "dimension", DIMENSION_TOP_LEVEL_LOAD,
                "unit", UNIT_LOAD,
                "storageIds", impactedStorageIds
            ), Long.class);
            int expected = result.storageRootCounts().entrySet().stream()
                .filter(entry -> impactedStorageIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .mapToInt(Integer::intValue).sum();
            if (activeCount == null || activeCount != expected) {
                throw new IllegalStateException("装拆后活动占用回读数量不一致");
            }
        }
    }

    private WmsLoadChangeResult replay(OccupancyEffectRow effect) {
        List<Long> historyIds = jdbcTemplate.queryForList("""
            SELECT relation_history_id
            FROM wms_load_relation_history
            WHERE effect_key = ?
            ORDER BY relation_history_id
            """, Long.class, effect.effectKey());
        List<Long> createdOccupancyIds = jdbcTemplate.queryForList("""
            SELECT occupancy_id
            FROM wms_storage_occupancy
            WHERE occupancy_effect_id = ?
            ORDER BY occupancy_id
            """, Long.class, effect.occupancyEffectId());
        List<Long> activeIds = jdbcTemplate.queryForList("""
            SELECT occupancy_id
            FROM wms_storage_occupancy
            WHERE occupancy_effect_id = ? AND status = 'ACTIVE'
            ORDER BY occupancy_id
            """, Long.class, effect.occupancyEffectId());
        List<RootCountRow> roots = jdbcTemplate.query("""
            SELECT i.storage_id, COUNT(*) AS root_count
            FROM wms_storage_info i
            WHERE i.location_kind = 'STORAGE'
            GROUP BY i.storage_id
            ORDER BY i.storage_id
            """, (result, index) -> new RootCountRow(
            result.getLong("storage_id"),
            result.getInt("root_count")
        ));
        Map<Long, Integer> counts = roots.stream().collect(Collectors.toMap(
            RootCountRow::storageId, RootCountRow::rootCount,
            (left, right) -> left, TreeMap::new));
        List<Long> rootIds = jdbcTemplate.queryForList("""
            SELECT n.load_node_id
            FROM wms_load_node n
            LEFT JOIN wms_load_relation r ON r.child_node_id = n.load_node_id
            WHERE r.child_node_id IS NULL
            ORDER BY n.load_node_id
            """, Long.class);
        return new WmsLoadChangeResult(
            effect.occupancyEffectId(),
            effect.effectKey(),
            historyIds,
            activeIds,
            List.of(),
            rootIds,
            counts
        );
    }

    private OccupancyEffectRow selectEffectForUpdate(String effectKey) {
        List<OccupancyEffectRow> rows = jdbcTemplate.query("""
            SELECT occupancy_effect_id, effect_key, business_type, business_id,
                   business_line_id, task_key, plan_instance_id, trace_id
            FROM wms_occupancy_effect
            WHERE effect_key = ?
            FOR UPDATE
            """, (result, index) -> new OccupancyEffectRow(
            result.getLong("occupancy_effect_id"),
            result.getString("effect_key"),
            result.getString("business_type"),
            result.getLong("business_id"),
            nullableLong(result, "business_line_id"),
            result.getString("task_key"),
            nullableLong(result, "plan_instance_id"),
            nullableLong(result, "trace_id")
        ), effectKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void verifyEffect(WmsLoadChangeCommand command, OccupancyEffectRow effect) {
        if (!Objects.equals(effect.businessType(), command.businessType())
            || effect.businessId() != command.businessId()
            || !Objects.equals(effect.businessLineId(), command.businessLineId())
            || !Objects.equals(effect.taskKey(), command.taskKey())
            || !Objects.equals(effect.planInstanceId(), command.planInstanceId())
            || command.traceId() != null
            && !Objects.equals(effect.traceId(), command.traceId())) {
            throw conflict(ERROR_RELATION_CONFLICT, "同一效果键已绑定不同装拆业务");
        }
    }

    private void validate(WmsLoadChangeCommand command) {
        if (command == null || command.mutations() == null || command.mutations().isEmpty()) {
            throw invalid("装拆命令和变更明细不能为空");
        }
        if (command.effectKey() == null
            || !EFFECT_KEY_PATTERN.matcher(command.effectKey()).matches()
            || command.inputHash() == null
            || !HASH_PATTERN.matcher(command.inputHash()).matches()
            || !command.effectKey().equals("WMS:" + command.inputHash())) {
            throw invalid("effectKey必须为WMS:加inputHash，inputHash为小写64位SHA-256");
        }
        requireText(command.businessType(), "businessType", 64);
        requireText(command.businessName(), "businessName", 128);
        requirePositive(command.businessId(), "businessId");
        optionalText(command.businessNo(), "businessNo", 100);
        optionalText(command.taskKey(), "taskKey", 96);
        optionalText(command.definitionVersion(), "definitionVersion", 64);
        optionalText(command.reason(), "reason", TEXT_LIMIT);
        optionalText(command.evidenceRef(), "evidenceRef", TEXT_LIMIT);
        optionalText(command.sourceSystem(), "sourceSystem", 64);
        optionalText(command.requestId(), "requestId", 64);
        if (command.occurredAt() == null) {
            throw invalid("occurredAt不能为空");
        }
        validateOptionalPositive(command.businessLineId(), "businessLineId");
        validateOptionalPositive(command.planInstanceId(), "planInstanceId");
        validateOptionalPositive(command.eventId(), "eventId");
        validateOptionalPositive(command.attemptId(), "attemptId");
        validateOptionalPositive(command.actorId(), "actorId");
        validateOptionalPositive(command.departmentId(), "departmentId");
        validateOptionalPositive(command.traceId(), "traceId");
        Set<Long> children = new HashSet<>();
        for (WmsLoadMutation mutation : command.mutations()) {
            if (mutation == null || mutation.childNodeId() <= 0
                || !children.add(mutation.childNodeId())
                || mutation.expectedNodeRevision() < 1) {
                throw invalid("装拆子节点为空、重复或修订号非法");
            }
            validateOptionalPositive(mutation.expectedParentNodeId(), "expectedParentNodeId");
            validateOptionalPositive(mutation.targetParentNodeId(), "targetParentNodeId");
            if (mutation.targetParentNodeId() == null) {
                validateRootLocation(mutation);
            } else if (mutation.targetLocationKind() != null
                || mutation.targetStorageId() != null) {
                throw invalid("装入载具时不能同时指定根位置");
            }
        }
    }

    private void validateRootLocation(WmsLoadMutation mutation) {
        if (!LOCATION_KINDS.contains(mutation.targetLocationKind())) {
            throw invalid("根节点位置类型不支持: " + mutation.targetLocationKind());
        }
        if (LOCATION_STORAGE.equals(mutation.targetLocationKind())) {
            if (mutation.targetStorageId() == null || mutation.targetStorageId() <= 0) {
                throw invalid("储位根节点必须指定物理储位");
            }
        } else if (mutation.targetStorageId() != null) {
            throw invalid("非储位根节点不能指定物理储位");
        }
    }

    private void requirePositive(long value, String field) {
        if (value <= 0) {
            throw invalid(field + "必须大于0");
        }
    }

    private void validateOptionalPositive(Long value, String field) {
        if (value != null && value <= 0) {
            throw invalid(field + "必须大于0或为null");
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + "不能为空");
        }
        if (value.length() > maxLength) {
            throw invalid(field + "超过最大长度" + maxLength);
        }
    }

    private void optionalText(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw invalid(field + "超过最大长度" + maxLength);
        }
    }

    private long nextId() {
        Number generated = identifierGenerator.nextId(null);
        if (generated == null || generated.longValue() <= 0) {
            throw new IllegalStateException("装拆事实主键生成失败");
        }
        return generated.longValue();
    }

    private static Long nullableLong(java.sql.ResultSet result, String column)
        throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private ServiceException invalid(String message) {
        return error(ERROR_INVALID_COMMAND, message, HttpStatus.HTTP_BAD_REQUEST);
    }

    private ServiceException conflict(String code, String message) {
        return error(code, message, HttpStatus.HTTP_CONFLICT);
    }

    private ServiceException ledgerDrift(String message) {
        return conflict(ERROR_LEDGER_DRIFT, message);
    }

    private ServiceException error(String code, String message, int status) {
        return new ServiceException(code + ": " + message, status);
    }

    private record LoadGraph(
        List<NodeRow> nodes,
        Map<Long, RelationRow> relationsByChild,
        Map<Long, LocationRow> locationsByRoot,
        Map<Long, NodeRow> nodesById,
        Map<Long, Long> parentByChild
    ) {
    }

    private record NodeRow(
        long nodeId,
        LoadNode.Type nodeType,
        Long inventoryId,
        Long containerId,
        int revision
    ) {
    }

    private record RelationRow(
        long relationId,
        long childNodeId,
        long parentNodeId,
        int revision
    ) {
    }

    private record LocationRow(
        long storageInfoId,
        long rootNodeId,
        String locationKind,
        Long storageId,
        int revision
    ) {
    }

    private record ChannelRow(long channelId, String status, int revision) {
    }

    private record StorageRow(
        long storageId,
        String storageCode,
        int status,
        int lockStatus,
        int disableStatus,
        String delFlag,
        int version
    ) {
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
        long occupancyEffectId,
        long storageId,
        long rootNodeId,
        String dimensionKey,
        BigDecimal occupiedQty,
        String unit,
        int generation,
        String status
    ) {
    }

    private record OccupancyEffectRow(
        long occupancyEffectId,
        String effectKey,
        String businessType,
        long businessId,
        Long businessLineId,
        String taskKey,
        Long planInstanceId,
        Long traceId
    ) {
    }

    private record AllocationLockRow(
        long allocationLockId,
        long storageId,
        String resourceDomain,
        String lockKind,
        String dimensionKey,
        String unit,
        BigDecimal lockedQty,
        String effectResult
    ) {
    }

    private record RootCountRow(long storageId, int rootCount) {
    }

    private record OccupancyKey(long storageId, long rootNodeId)
        implements Comparable<OccupancyKey> {

        @Override
        public int compareTo(OccupancyKey other) {
            int storage = Long.compare(storageId, other.storageId);
            return storage != 0 ? storage : Long.compare(rootNodeId, other.rootNodeId);
        }
    }
}
