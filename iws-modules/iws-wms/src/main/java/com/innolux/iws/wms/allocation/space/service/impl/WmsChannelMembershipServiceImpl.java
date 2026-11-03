/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道成员共锁
 * @Version      : V1.0
 * @Description  : 按通道升序、储位次序共锁并阻断活动锁、UNKNOWN和占用；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelAccessOrderChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelAccessOrderChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelDefinitionChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelDefinitionChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelMembershipChangeCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsChannelMembershipChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeAddCommand;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeChangeResult;
import com.innolux.iws.wms.allocation.space.domain.WmsStorageAccessEdgeRemoveCommand;
import com.innolux.iws.wms.allocation.space.service.WmsChannelMembershipService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 普通储位通道成员变更短事务实现。 */
@Service
public class WmsChannelMembershipServiceImpl implements WmsChannelMembershipService {

    private static final Set<String> CHANNEL_STATUSES =
        Set.of("ACTIVE", "RETIRING", "RETIRED");

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final IdentifierGenerator identifierGenerator;

    public WmsChannelMembershipServiceImpl(
        JdbcTemplate jdbcTemplate,
        IdentifierGenerator identifierGenerator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.identifierGenerator = identifierGenerator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsChannelMembershipChangeResult change(WmsChannelMembershipChangeCommand command) {
        validate(command);
        MembershipRow initial = loadMembership(command.storageId(), false);
        if (initial == null && command.targetChannelId() == null) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "储位当前没有通道成员可移出");
        }
        validateExpectedCurrent(command, initial);
        List<Long> channelIds = channelIds(initial, command.targetChannelId());
        Map<Long, Integer> channelRevisions =
            lockChannels(channelIds, command.targetChannelId());
        lockStorages(Set.of(command.storageId()));
        MembershipRow current = loadMembership(command.storageId(), true);
        assertUnchanged(initial, current);
        validateExpectedRevisions(command, current, channelRevisions);
        rejectBusyResources(channelIds, Set.of(command.storageId()));

        Long memberId;
        Integer memberRevision;
        Integer accessOrder;
        if (command.targetChannelId() == null) {
            deleteMember(current);
            memberId = null;
            memberRevision = null;
            accessOrder = null;
        } else {
            ensureAccessOrderAvailable(
                command.targetChannelId(), command.targetAccessOrder(), current);
            if (current == null) {
                memberId = nextId();
                memberRevision = 1;
                insertMember(command, memberId);
            } else if (current.channelId() == command.targetChannelId()) {
                memberId = current.memberId();
                memberRevision = current.revision() + 1;
                updateMember(command, current, memberRevision);
            } else {
                deleteMember(current);
                memberId = nextId();
                memberRevision = 1;
                insertMember(command, memberId);
            }
            accessOrder = command.targetAccessOrder();
        }

        Map<Long, Integer> after = new LinkedHashMap<>();
        for (Long channelId : channelIds) {
            after.put(channelId, incrementChannelRevision(command, channelId,
                channelRevisions.get(channelId)));
        }
        Long oldChannelId = initial == null ? null : initial.channelId();
        Long newChannelId = command.targetChannelId();
        return new WmsChannelMembershipChangeResult(
            command.storageId(),
            oldChannelId,
            newChannelId,
            memberId,
            accessOrder,
            memberRevision,
            oldChannelId == null ? null : after.get(oldChannelId),
            newChannelId == null ? null : after.get(newChannelId)
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsChannelAccessOrderChangeResult reorder(
        WmsChannelAccessOrderChangeCommand command
    ) {
        validate(command);
        List<MembershipRow> initial = loadChannelMembers(command.channelId(), false);
        if (initial.isEmpty()) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "通道没有可调整的成员");
        }
        Set<Long> storageIds = initial.stream()
            .map(MembershipRow::storageId)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<Long, Integer> channelRevisions =
            lockChannels(List.of(command.channelId()), command.channelId());
        lockStorages(storageIds);
        List<MembershipRow> current = loadChannelMembers(command.channelId(), true);
        assertChannelMembersUnchanged(initial, current);
        if (channelRevisions.get(command.channelId()) != command.expectedChannelRevision()) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "通道修订号与期望不一致");
        }
        rejectBusyResources(List.of(command.channelId()), storageIds);

        Map<Long, MembershipRow> currentById = current.stream().collect(Collectors.toMap(
            MembershipRow::memberId,
            row -> row,
            (left, right) -> left,
            LinkedHashMap::new
        ));
        Map<Long, WmsChannelAccessOrderChangeCommand.MemberOrder> requestedById =
            command.members().stream().collect(Collectors.toMap(
                WmsChannelAccessOrderChangeCommand.MemberOrder::memberId,
                row -> row,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        for (Long memberId : requestedById.keySet()) {
            if (!currentById.containsKey(memberId)) {
                throw conflict(ERROR_MEMBERSHIP_CONFLICT,
                    "待调整成员不属于目标通道: " + memberId);
            }
        }
        Set<Integer> requestedOrders = requestedById.values().stream()
            .map(WmsChannelAccessOrderChangeCommand.MemberOrder::targetAccessOrder)
            .collect(Collectors.toSet());
        boolean occupiedByUnchanged = current.stream()
            .filter(row -> !requestedById.containsKey(row.memberId()))
            .anyMatch(row -> requestedOrders.contains(row.accessOrder()));
        if (occupiedByUnchanged) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "目标访问顺序已被未调整成员占用");
        }

        int maxAccessOrder = current.stream()
            .mapToInt(MembershipRow::accessOrder)
            .max()
            .orElse(0);
        int maxTargetOrder = requestedById.values().stream()
            .mapToInt(WmsChannelAccessOrderChangeCommand.MemberOrder::targetAccessOrder)
            .max()
            .orElse(0);
        int highestOrder = Math.max(maxAccessOrder, maxTargetOrder);
        if (highestOrder > Integer.MAX_VALUE - requestedById.size() - 1) {
            throw invalid("访问顺序临时值超出整数范围");
        }
        int temporaryBase = highestOrder + 1;
        List<WmsChannelAccessOrderChangeCommand.MemberOrder> orderedRequests =
            requestedById.values().stream()
                .sorted(Comparator.comparingLong(
                    WmsChannelAccessOrderChangeCommand.MemberOrder::memberId))
                .toList();
        for (int index = 0; index < orderedRequests.size(); index++) {
            WmsChannelAccessOrderChangeCommand.MemberOrder requested =
                orderedRequests.get(index);
            int rows = jdbcTemplate.update("""
                UPDATE wms_storage_access_member
                SET access_order = ?
                WHERE member_id = ? AND channel_id = ? AND revision = ?
                """,
                temporaryBase + index,
                requested.memberId(),
                command.channelId(),
                requested.expectedRevision()
            );
            if (rows != 1) {
                throw conflict(ERROR_MEMBERSHIP_CONFLICT,
                    "成员修订号已变化: " + requested.memberId());
            }
        }

        List<WmsChannelAccessOrderChangeResult.MemberOrderResult> results = new ArrayList<>();
        for (WmsChannelAccessOrderChangeCommand.MemberOrder requested : orderedRequests) {
            MembershipRow row = currentById.get(requested.memberId());
            int rows = jdbcTemplate.update("""
                UPDATE wms_storage_access_member
                SET access_order = ?, revision = revision + 1,
                    update_by = ?, update_time = ?
                WHERE member_id = ? AND channel_id = ? AND revision = ?
                """,
                requested.targetAccessOrder(),
                command.actorId(),
                command.occurredAt(),
                requested.memberId(),
                command.channelId(),
                requested.expectedRevision()
            );
            if (rows != 1) {
                throw conflict(ERROR_MEMBERSHIP_CONFLICT,
                    "成员修订号已变化: " + requested.memberId());
            }
            results.add(new WmsChannelAccessOrderChangeResult.MemberOrderResult(
                requested.memberId(),
                row.storageId(),
                row.accessOrder(),
                requested.targetAccessOrder(),
                requested.expectedRevision() + 1
            ));
        }
        int channelRevision = incrementChannelRevision(
            command.channelId(),
            command.expectedChannelRevision(),
            command.occurredAt(),
            command.actorId()
        );
        return new WmsChannelAccessOrderChangeResult(
            command.channelId(),
            channelRevision,
            results.stream()
                .sorted(Comparator.comparingInt(
                    WmsChannelAccessOrderChangeResult.MemberOrderResult::accessOrder))
                .toList()
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsChannelDefinitionChangeResult changeDefinition(
        WmsChannelDefinitionChangeCommand command
    ) {
        validate(command);
        ChannelRow initialChannel = loadChannel(command.channelId(), false);
        if (initialChannel == null) {
            throw conflict(ERROR_CONFIGURATION_CONFLICT, "通道不存在");
        }
        List<MembershipRow> initialMembers = loadChannelMembers(command.channelId(), false);
        Set<Long> storageIds = initialMembers.stream()
            .map(MembershipRow::storageId)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        lockChannelRows(
            List.of(command.channelId()), null, ERROR_CONFIGURATION_CONFLICT);
        lockStorages(storageIds);
        List<MembershipRow> currentMembers = loadChannelMembers(command.channelId(), true);
        assertChannelMembersUnchanged(initialMembers, currentMembers);
        ChannelRow currentChannel = loadChannel(command.channelId(), true);
        if (currentChannel == null) {
            throw conflict(ERROR_CONFIGURATION_CONFLICT, "通道不存在");
        }
        if (!Objects.equals(initialChannel.channelCode(), currentChannel.channelCode())
            || !Objects.equals(initialChannel.accessMode(), currentChannel.accessMode())
            || !Objects.equals(initialChannel.status(), currentChannel.status())
            || initialChannel.revision() != currentChannel.revision()) {
            throw conflict(ERROR_CONFIGURATION_CONFLICT, "通道定义在锁前变化");
        }
        if (currentChannel.revision() != command.expectedRevision()) {
            throw conflict(ERROR_CONFIGURATION_CONFLICT, "通道修订号与期望不一致");
        }
        rejectBusyResources(List.of(command.channelId()), storageIds);
        int rows;
        try {
            rows = jdbcTemplate.update("""
                UPDATE wms_access_channel
                SET channel_code = ?, access_mode = ?, status = ?,
                    revision = revision + 1, update_by = ?, update_time = ?
                WHERE channel_id = ? AND revision = ?
                """,
                command.channelCode(),
                command.accessMode(),
                command.status(),
                command.actorId(),
                command.occurredAt(),
                command.channelId(),
                command.expectedRevision()
            );
        } catch (DuplicateKeyException error) {
            throw conflict(ERROR_CONFIGURATION_CONFLICT, "通道编码已被占用");
        }
        if (rows != 1) {
            throw conflict(ERROR_CONFIGURATION_CONFLICT, "通道修订号已变化");
        }
        return new WmsChannelDefinitionChangeResult(
            command.channelId(),
            command.channelCode(),
            command.accessMode(),
            command.status(),
            command.expectedRevision() + 1
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsStorageAccessEdgeChangeResult addEdge(
        WmsStorageAccessEdgeAddCommand command
    ) {
        validate(command);
        MembershipRow initialBlocker = loadMembership(command.blockerStorageId(), false);
        MembershipRow initialBlocked = loadMembership(command.blockedStorageId(), false);
        if (initialBlocker == null || initialBlocked == null) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边两侧储位必须属于访问通道");
        }
        List<Long> channelIds = channelIds(initialBlocker, initialBlocked);
        Set<Long> storageIds = Set.of(
            command.blockerStorageId(), command.blockedStorageId());
        Map<Long, ChannelRow> channels = lockChannelRows(
            channelIds, null, ERROR_EDGE_CONFLICT);
        requireActiveChannels(channels);
        lockStorages(storageIds);
        assertUnchanged(initialBlocker,
            loadMembership(command.blockerStorageId(), true));
        assertUnchanged(initialBlocked,
            loadMembership(command.blockedStorageId(), true));
        rejectBusyResources(channelIds, storageIds);
        if (!loadEdgeByPair(
            command.blockerStorageId(), command.blockedStorageId(), true).isEmpty()) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边已存在");
        }

        long accessEdgeId = nextId();
        try {
            jdbcTemplate.update("""
                INSERT INTO wms_storage_access_edge (
                  access_edge_id, blocker_storage_id, blocked_storage_id, revision,
                  create_dept, create_by, create_time, update_time
                ) VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                """,
                accessEdgeId,
                command.blockerStorageId(),
                command.blockedStorageId(),
                command.departmentId(),
                command.actorId(),
                command.occurredAt(),
                command.occurredAt()
            );
        } catch (DuplicateKeyException error) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边已存在");
        }
        return edgeResult(
            accessEdgeId,
            command.blockerStorageId(),
            command.blockedStorageId(),
            1,
            false,
            incrementChannels(
                channels, command.occurredAt(), command.actorId(), ERROR_EDGE_CONFLICT)
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsStorageAccessEdgeChangeResult removeEdge(
        WmsStorageAccessEdgeRemoveCommand command
    ) {
        validate(command);
        EdgeRow initialEdge = loadEdge(command.accessEdgeId(), false);
        if (initialEdge == null) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边不存在");
        }
        MembershipRow initialBlocker =
            loadMembership(initialEdge.blockerStorageId(), false);
        MembershipRow initialBlocked =
            loadMembership(initialEdge.blockedStorageId(), false);
        if (initialBlocker == null || initialBlocked == null) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边两侧储位必须属于访问通道");
        }
        List<Long> channelIds = channelIds(initialBlocker, initialBlocked);
        Set<Long> storageIds = Set.of(
            initialEdge.blockerStorageId(), initialEdge.blockedStorageId());
        Map<Long, ChannelRow> channels = lockChannelRows(
            channelIds, null, ERROR_EDGE_CONFLICT);
        lockStorages(storageIds);
        assertUnchanged(initialBlocker,
            loadMembership(initialEdge.blockerStorageId(), true));
        assertUnchanged(initialBlocked,
            loadMembership(initialEdge.blockedStorageId(), true));
        rejectBusyResources(channelIds, storageIds);
        EdgeRow currentEdge = loadEdge(command.accessEdgeId(), true);
        if (currentEdge == null
            || currentEdge.blockerStorageId() != initialEdge.blockerStorageId()
            || currentEdge.blockedStorageId() != initialEdge.blockedStorageId()
            || currentEdge.revision() != initialEdge.revision()) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边在锁前变化");
        }
        if (currentEdge.revision() != command.expectedRevision()) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边修订号与期望不一致");
        }
        int rows = jdbcTemplate.update("""
            DELETE FROM wms_storage_access_edge
            WHERE access_edge_id = ? AND revision = ?
            """, command.accessEdgeId(), command.expectedRevision());
        if (rows != 1) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边修订号已变化");
        }
        return edgeResult(
            currentEdge.accessEdgeId(),
            currentEdge.blockerStorageId(),
            currentEdge.blockedStorageId(),
            currentEdge.revision(),
            true,
            incrementChannels(
                channels, command.occurredAt(), command.actorId(), ERROR_EDGE_CONFLICT)
        );
    }

    private MembershipRow loadMembership(long storageId, boolean forUpdate) {
        String sql = """
            SELECT member_id, channel_id, storage_id, access_order, revision
            FROM wms_storage_access_member
            WHERE storage_id = ?
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<MembershipRow> rows = jdbcTemplate.query(sql, (result, index) ->
            new MembershipRow(
                result.getLong("member_id"),
                result.getLong("channel_id"),
                result.getLong("storage_id"),
                result.getInt("access_order"),
                result.getInt("revision")
            ), storageId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<MembershipRow> loadChannelMembers(long channelId, boolean forUpdate) {
        String sql = """
            SELECT member_id, channel_id, storage_id, access_order, revision
            FROM wms_storage_access_member
            WHERE channel_id = ?
            ORDER BY member_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, (result, index) -> new MembershipRow(
            result.getLong("member_id"),
            result.getLong("channel_id"),
            result.getLong("storage_id"),
            result.getInt("access_order"),
            result.getInt("revision")
        ), channelId);
    }

    private ChannelRow loadChannel(long channelId, boolean forUpdate) {
        String sql = """
            SELECT channel_id, channel_code, access_mode, status, revision
            FROM wms_access_channel
            WHERE channel_id = ?
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<ChannelRow> rows = jdbcTemplate.query(sql, (result, index) ->
            new ChannelRow(
                result.getLong("channel_id"),
                result.getString("channel_code"),
                result.getString("access_mode"),
                result.getString("status"),
                result.getInt("revision")
            ), channelId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<Long, Integer> lockChannels(List<Long> channelIds, Long targetChannelId) {
        return lockChannelRows(channelIds, targetChannelId, ERROR_MEMBERSHIP_CONFLICT)
            .values().stream().collect(Collectors.toMap(
                ChannelRow::channelId,
                ChannelRow::revision,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private Map<Long, ChannelRow> lockChannelRows(
        List<Long> channelIds,
        Long targetChannelId,
        String conflictCode
    ) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        List<ChannelRow> rows = namedJdbc.query("""
            SELECT channel_id, channel_code, access_mode, status, revision
            FROM wms_access_channel
            WHERE channel_id IN (:channelIds)
            ORDER BY channel_id
            FOR UPDATE
            """, Map.of("channelIds", channelIds), (result, index) -> new ChannelRow(
            result.getLong("channel_id"),
            result.getString("channel_code"),
            result.getString("access_mode"),
            result.getString("status"),
            result.getInt("revision")
        ));
        if (rows.size() != channelIds.size()) {
            throw conflict(conflictCode, "通道不存在");
        }
        if (targetChannelId != null
            && rows.stream()
                .filter(row -> row.channelId() == targetChannelId)
                .anyMatch(row -> !"ACTIVE".equals(row.status()))) {
            throw conflict(conflictCode, "目标通道不是ACTIVE");
        }
        return rows.stream().collect(Collectors.toMap(
            ChannelRow::channelId,
            row -> row,
            (left, right) -> left,
            LinkedHashMap::new
        ));
    }

    private void requireActiveChannels(Map<Long, ChannelRow> channels) {
        if (channels.values().stream().anyMatch(row -> !"ACTIVE".equals(row.status()))) {
            throw conflict(ERROR_EDGE_CONFLICT, "阻挡边关联通道不是ACTIVE");
        }
    }

    private void lockStorages(Set<Long> storageIds) {
        if (storageIds.isEmpty()) {
            return;
        }
        List<Long> rows = namedJdbc.queryForList("""
            SELECT storage_id FROM wms_storage
            WHERE storage_id IN (:storageIds) AND del_flag = '0'
            ORDER BY storage_id
            FOR UPDATE
            """, Map.of("storageIds", storageIds), Long.class);
        if (rows.size() != storageIds.size()) {
            throw invalid("成员变更储位不存在");
        }
    }

    private void rejectBusyResources(List<Long> channelIds, Set<Long> storageIds) {
        List<Long> occupancies = namedJdbc.queryForList("""
            SELECT occupancy_id FROM wms_storage_occupancy
            WHERE status = 'ACTIVE' AND storage_id IN (:storageIds)
            ORDER BY storage_id, occupancy_id
            FOR UPDATE
            """, Map.of("storageIds", storageIds), Long.class);
        if (!occupancies.isEmpty()) {
            throw conflict(ERROR_CHANNEL_BUSY, "储位仍有活动占用");
        }

        List<String> storageEffects = namedJdbc.query("""
            SELECT effect_result FROM wms_allocation_lock
            WHERE status = 'LOCKED' AND storage_id IN (:storageIds)
            ORDER BY storage_id, allocation_lock_id
            FOR UPDATE
            """, Map.of("storageIds", storageIds), (result, index) ->
            result.getString("effect_result"));
        rejectUnknownOrBusy(storageEffects, "储位存在活动分配锁");

        List<String> channelEffects = namedJdbc.query("""
            SELECT effect_result FROM wms_access_channel_lock
            WHERE status = 'LOCKED' AND channel_id IN (:channelIds)
            ORDER BY channel_lock_id
            FOR UPDATE
            """, Map.of("channelIds", channelIds), (result, index) ->
            result.getString("effect_result"));
        rejectUnknownOrBusy(channelEffects, "通道存在活动分配锁");
    }

    private void rejectUnknownOrBusy(List<String> effects, String busyMessage) {
        if (effects.stream().anyMatch("UNKNOWN"::equals)) {
            throw conflict(ERROR_EVIDENCE_UNKNOWN, "存在UNKNOWN外部效果，禁止变更通道空间配置");
        }
        if (!effects.isEmpty()) {
            throw conflict(ERROR_CHANNEL_BUSY, busyMessage);
        }
    }

    private void ensureAccessOrderAvailable(
        long channelId,
        int accessOrder,
        MembershipRow current
    ) {
        List<Long> owners = jdbcTemplate.queryForList("""
            SELECT member_id FROM wms_storage_access_member
            WHERE channel_id = ? AND access_order = ?
            FOR UPDATE
            """, Long.class, channelId, accessOrder);
        if (owners.stream().anyMatch(owner ->
            current == null || owner != current.memberId())) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "目标访问顺序已被占用");
        }
    }

    private void insertMember(WmsChannelMembershipChangeCommand command, long memberId) {
        try {
            jdbcTemplate.update("""
                INSERT INTO wms_storage_access_member (
                  member_id, channel_id, storage_id, access_order, revision,
                  create_dept, create_by, create_time, update_time
                ) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)
                """,
                memberId,
                command.targetChannelId(),
                command.storageId(),
                command.targetAccessOrder(),
                command.departmentId(),
                command.actorId(),
                command.occurredAt(),
                command.occurredAt()
            );
        } catch (DuplicateKeyException error) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "储位或访问顺序已被其他成员占用");
        }
    }

    private void updateMember(
        WmsChannelMembershipChangeCommand command,
        MembershipRow current,
        int revision
    ) {
        int rows = jdbcTemplate.update("""
            UPDATE wms_storage_access_member
            SET access_order = ?, revision = ?, update_by = ?, update_time = ?
            WHERE member_id = ? AND revision = ?
            """,
            command.targetAccessOrder(),
            revision,
            command.actorId(),
            command.occurredAt(),
            current.memberId(),
            current.revision()
        );
        if (rows != 1) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "成员修订号已变化");
        }
    }

    private void deleteMember(MembershipRow current) {
        if (current == null) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "储位当前没有通道成员");
        }
        int rows = jdbcTemplate.update("""
            DELETE FROM wms_storage_access_member
            WHERE member_id = ? AND revision = ?
            """, current.memberId(), current.revision());
        if (rows != 1) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "成员修订号已变化");
        }
    }

    private int incrementChannelRevision(
        WmsChannelMembershipChangeCommand command,
        long channelId,
        int currentRevision
    ) {
        return incrementChannelRevision(
            channelId,
            currentRevision,
            command.occurredAt(),
            command.actorId()
        );
    }

    private int incrementChannelRevision(
        long channelId,
        int currentRevision,
        LocalDateTime occurredAt,
        Long actorId
    ) {
        return incrementChannelRevision(
            channelId,
            currentRevision,
            occurredAt,
            actorId,
            ERROR_MEMBERSHIP_CONFLICT
        );
    }

    private int incrementChannelRevision(
        long channelId,
        int currentRevision,
        LocalDateTime occurredAt,
        Long actorId,
        String conflictCode
    ) {
        int rows = jdbcTemplate.update("""
            UPDATE wms_access_channel
            SET revision = revision + 1, update_by = ?, update_time = ?
            WHERE channel_id = ? AND revision = ?
            """,
            actorId,
            occurredAt,
            channelId,
            currentRevision
        );
        if (rows != 1) {
            throw conflict(conflictCode, "通道修订号已变化: " + channelId);
        }
        return currentRevision + 1;
    }

    private List<WmsStorageAccessEdgeChangeResult.ChannelRevision> incrementChannels(
        Map<Long, ChannelRow> channels,
        LocalDateTime occurredAt,
        Long actorId,
        String conflictCode
    ) {
        return channels.values().stream()
            .sorted(Comparator.comparingLong(ChannelRow::channelId))
            .map(channel -> new WmsStorageAccessEdgeChangeResult.ChannelRevision(
                channel.channelId(),
                incrementChannelRevision(
                    channel.channelId(),
                    channel.revision(),
                    occurredAt,
                    actorId,
                    conflictCode
                )
            ))
            .toList();
    }

    private List<EdgeRow> loadEdgeByPair(
        long blockerStorageId,
        long blockedStorageId,
        boolean forUpdate
    ) {
        String sql = """
            SELECT access_edge_id, blocker_storage_id, blocked_storage_id, revision
            FROM wms_storage_access_edge
            WHERE blocker_storage_id = ? AND blocked_storage_id = ?
            """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, (result, index) -> new EdgeRow(
            result.getLong("access_edge_id"),
            result.getLong("blocker_storage_id"),
            result.getLong("blocked_storage_id"),
            result.getInt("revision")
        ), blockerStorageId, blockedStorageId);
    }

    private EdgeRow loadEdge(long accessEdgeId, boolean forUpdate) {
        String sql = """
            SELECT access_edge_id, blocker_storage_id, blocked_storage_id, revision
            FROM wms_storage_access_edge
            WHERE access_edge_id = ?
            """ + (forUpdate ? " FOR UPDATE" : "");
        List<EdgeRow> rows = jdbcTemplate.query(sql, (result, index) -> new EdgeRow(
            result.getLong("access_edge_id"),
            result.getLong("blocker_storage_id"),
            result.getLong("blocked_storage_id"),
            result.getInt("revision")
        ), accessEdgeId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private WmsStorageAccessEdgeChangeResult edgeResult(
        long accessEdgeId,
        long blockerStorageId,
        long blockedStorageId,
        int edgeRevision,
        boolean removed,
        List<WmsStorageAccessEdgeChangeResult.ChannelRevision> channelRevisions
    ) {
        return new WmsStorageAccessEdgeChangeResult(
            accessEdgeId,
            blockerStorageId,
            blockedStorageId,
            edgeRevision,
            removed,
            channelRevisions
        );
    }

    private List<Long> channelIds(MembershipRow current, Long targetChannelId) {
        List<Long> ids = new ArrayList<>();
        if (current != null) {
            ids.add(current.channelId());
        }
        if (targetChannelId != null) {
            ids.add(targetChannelId);
        }
        return ids.stream().distinct().sorted().toList();
    }

    private List<Long> channelIds(MembershipRow blocker, MembershipRow blocked) {
        return List.of(blocker.channelId(), blocked.channelId()).stream()
            .distinct()
            .sorted()
            .toList();
    }

    private void validateExpectedCurrent(
        WmsChannelMembershipChangeCommand command,
        MembershipRow current
    ) {
        if (current == null) {
            if (command.expectedCurrentChannelId() != null
                || command.expectedMemberId() != null
                || command.expectedMemberRevision() != 0
                || command.expectedCurrentChannelRevision() != null) {
                throw conflict(ERROR_MEMBERSHIP_CONFLICT, "储位成员存在性与期望不一致");
            }
            return;
        }
        if (!Objects.equals(command.expectedCurrentChannelId(), current.channelId())
            || !Objects.equals(command.expectedMemberId(), current.memberId())
            || !Objects.equals(command.expectedMemberRevision(), current.revision())) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "储位当前成员与期望不一致");
        }
    }

    private void validateExpectedRevisions(
        WmsChannelMembershipChangeCommand command,
        MembershipRow current,
        Map<Long, Integer> revisions
    ) {
        if (current != null
            && !Objects.equals(command.expectedCurrentChannelRevision(),
                revisions.get(current.channelId()))) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "当前通道修订号与期望不一致");
        }
        if (command.targetChannelId() != null
            && !Objects.equals(command.expectedTargetChannelRevision(),
                revisions.get(command.targetChannelId()))) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "目标通道修订号与期望不一致");
        }
    }

    private void assertUnchanged(MembershipRow initial, MembershipRow current) {
        if (initial == null && current == null) {
            return;
        }
        if (initial == null || current == null
            || initial.memberId() != current.memberId()
            || initial.channelId() != current.channelId()
            || initial.accessOrder() != current.accessOrder()
            || initial.revision() != current.revision()) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "储位成员在锁前变化");
        }
    }

    private void assertChannelMembersUnchanged(
        List<MembershipRow> initial,
        List<MembershipRow> current
    ) {
        if (initial.size() != current.size()) {
            throw conflict(ERROR_MEMBERSHIP_CONFLICT, "通道成员集合在锁前变化");
        }
        for (int index = 0; index < initial.size(); index++) {
            MembershipRow before = initial.get(index);
            MembershipRow after = current.get(index);
            if (before.memberId() != after.memberId()
                || before.storageId() != after.storageId()
                || before.accessOrder() != after.accessOrder()
                || before.revision() != after.revision()) {
                throw conflict(ERROR_MEMBERSHIP_CONFLICT, "通道成员在锁前变化");
            }
        }
    }

    private void validate(WmsChannelMembershipChangeCommand command) {
        if (command == null || command.storageId() <= 0 || command.occurredAt() == null) {
            throw invalid("成员变更储位和发生时间不能为空");
        }
        boolean remove = command.targetChannelId() == null;
        if (remove) {
            if (command.targetAccessOrder() != null
                || command.expectedTargetChannelRevision() != null) {
                throw invalid("移出成员不得提供目标顺序或目标通道修订号");
            }
        } else if (command.targetChannelId() <= 0
            || command.targetAccessOrder() == null
            || command.targetAccessOrder() < 0
            || command.expectedTargetChannelRevision() == null
            || command.expectedTargetChannelRevision() < 1) {
            throw invalid("新增或移动成员必须提供目标通道、非负顺序和正数通道修订号");
        }
        if (command.expectedMemberRevision() == null
            || command.expectedMemberRevision() < 0) {
            throw invalid("expectedMemberRevision不能为空或为负");
        }
        if (command.expectedCurrentChannelRevision() != null
            && command.expectedCurrentChannelRevision() < 1) {
            throw invalid("expectedCurrentChannelRevision必须为正数或null");
        }
        if (command.expectedCurrentChannelId() != null
            && command.expectedCurrentChannelId() <= 0) {
            throw invalid("expectedCurrentChannelId必须为正数或null");
        }
        if (command.expectedMemberId() != null && command.expectedMemberId() <= 0) {
            throw invalid("expectedMemberId必须为正数或null");
        }
        if (command.actorId() == null || command.actorId() <= 0) {
            throw invalid("actorId必须为正数");
        }
        if (command.departmentId() != null && command.departmentId() <= 0) {
            throw invalid("departmentId必须为正数或null");
        }
    }

    private void validate(WmsChannelAccessOrderChangeCommand command) {
        if (command == null || command.channelId() <= 0
            || command.expectedChannelRevision() <= 0
            || command.members() == null || command.members().isEmpty()
            || command.occurredAt() == null) {
            throw invalid("访问顺序变更命令不完整");
        }
        Set<Long> memberIds = new HashSet<>();
        Set<Integer> accessOrders = new HashSet<>();
        for (WmsChannelAccessOrderChangeCommand.MemberOrder member : command.members()) {
            if (member == null || member.memberId() <= 0
                || member.expectedRevision() <= 0
                || member.targetAccessOrder() < 0
                || !memberIds.add(member.memberId())
                || !accessOrders.add(member.targetAccessOrder())) {
                throw invalid("访问顺序变更成员非法、重复或目标顺序重复");
            }
        }
        if (command.actorId() == null || command.actorId() <= 0) {
            throw invalid("actorId必须为正数");
        }
        if (command.departmentId() != null && command.departmentId() <= 0) {
            throw invalid("departmentId必须为正数或null");
        }
    }

    private void validate(WmsChannelDefinitionChangeCommand command) {
        if (command == null || command.channelId() <= 0
            || command.expectedRevision() <= 0
            || command.occurredAt() == null) {
            throw invalid("通道定义变更命令不完整");
        }
        requireText(command.channelCode(), "channelCode", 64);
        requireText(command.accessMode(), "accessMode", 32);
        if (!CHANNEL_STATUSES.contains(command.status())) {
            throw invalid("通道状态不支持");
        }
        validateActorAndDepartment(command.actorId(), command.departmentId());
    }

    private void validate(WmsStorageAccessEdgeAddCommand command) {
        if (command == null || command.blockerStorageId() <= 0
            || command.blockedStorageId() <= 0
            || command.blockerStorageId() == command.blockedStorageId()
            || command.occurredAt() == null) {
            throw invalid("阻挡边储位非法或相同");
        }
        validateActorAndDepartment(command.actorId(), command.departmentId());
    }

    private void validate(WmsStorageAccessEdgeRemoveCommand command) {
        if (command == null || command.accessEdgeId() <= 0
            || command.expectedRevision() <= 0
            || command.occurredAt() == null) {
            throw invalid("阻挡边删除命令不完整");
        }
        validateActorAndDepartment(command.actorId(), command.departmentId());
    }

    private void validateActorAndDepartment(Long actorId, Long departmentId) {
        if (actorId == null || actorId <= 0) {
            throw invalid("actorId必须为正数");
        }
        if (departmentId != null && departmentId <= 0) {
            throw invalid("departmentId必须为正数或null");
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + "不能为空且长度不能超过" + maxLength);
        }
    }

    private long nextId() {
        Number id = identifierGenerator.nextId(null);
        if (id == null || id.longValue() <= 0) {
            throw new IllegalStateException("通道成员主键生成失败");
        }
        return id.longValue();
    }

    private ServiceException invalid(String message) {
        return new ServiceException(
            ERROR_INPUT_INVALID + ": " + message, HttpStatus.HTTP_BAD_REQUEST);
    }

    private ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, HttpStatus.HTTP_CONFLICT);
    }

    private record MembershipRow(
        long memberId,
        long channelId,
        long storageId,
        int accessOrder,
        int revision
    ) {
    }

    private record ChannelRow(
        long channelId,
        String channelCode,
        String accessMode,
        String status,
        int revision
    ) {
    }

    private record EdgeRow(
        long accessEdgeId,
        long blockerStorageId,
        long blockedStorageId,
        int revision
    ) {
    }
}
