/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道可达性
 * @Version      : V1.0
 * @Description  : 从一致性只读事务读取通道显式配置和活动占用并计算可达性；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.reachability;

import com.innolux.iws.common.core.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单通道可达性只读快照服务。
 *
 * <p>全部读取在同一 REPEATABLE READ 事务中完成。快照包含通道、成员、阻挡边和活动
 * 占用的 revision，并对其规范序列计算 SHA-256，供后续分配任务冻结。</p>
 */
@Service
public class WmsChannelReachabilitySnapshotService {

    public static final String ERROR_CHANNEL_NOT_FOUND = "CHANNEL_NOT_FOUND";

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final WmsChannelReachabilityEvaluator evaluator;

    public WmsChannelReachabilitySnapshotService(
        JdbcTemplate jdbcTemplate,
        WmsChannelReachabilityEvaluator evaluator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.evaluator = evaluator;
    }

    /**
     * 读取单个通道的显式阻挡图快照。
     *
     * @param channelId 通道 ID
     * @return 一致性只读快照
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WmsChannelReachabilitySnapshot snapshot(long channelId) {
        return snapshot(channelId, false);
    }

    /**
     * 在现有事务内以当前读锁定并读取单通道显式空间事实。
     *
     * @param channelId 通道 ID
     * @return 锁后当前读快照
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsChannelReachabilitySnapshot snapshotForUpdate(long channelId) {
        return snapshot(channelId, true);
    }

    private WmsChannelReachabilitySnapshot snapshot(long channelId, boolean forUpdate) {
        if (channelId <= 0) {
            throw new ServiceException(ERROR_CHANNEL_NOT_FOUND + ": 通道ID非法");
        }
        ChannelFact channel = loadChannel(channelId, forUpdate);
        List<WmsChannelReachabilitySnapshot.MemberFact> members = loadMembers(channelId, forUpdate);
        List<WmsChannelReachabilitySnapshot.EdgeFact> edges = members.isEmpty()
            ? List.of() : loadEdges(channelId, forUpdate);
        List<WmsChannelReachabilitySnapshot.OccupancyFact> occupancies =
            loadActiveOccupancies(members, forUpdate);
        Set<Long> occupiedStorageIds = new LinkedHashSet<>();
        occupancies.forEach(item -> occupiedStorageIds.add(item.storageId()));

        WmsChannelReachabilityResult reachability = evaluator.evaluate(
            members.stream()
                .map(item -> new WmsChannelReachabilityEvaluator.Member(
                    item.storageId(), item.accessOrder()))
                .toList(),
            edges.stream()
                .map(item -> new WmsChannelReachabilityEvaluator.BlockingEdge(
                    item.blockerStorageId(), item.blockedStorageId()))
                .toList(),
            occupiedStorageIds
        );
        String hash = snapshotHash(channel, members, edges, occupancies);
        return new WmsChannelReachabilitySnapshot(
            channel.channelId(),
            channel.channelCode(),
            channel.status(),
            channel.revision(),
            members,
            edges,
            occupancies,
            reachability,
            hash
        );
    }

    private ChannelFact loadChannel(long channelId, boolean forUpdate) {
        List<ChannelFact> rows = jdbcTemplate.query("""
            SELECT channel_id, channel_code, status, revision
            FROM wms_access_channel
            WHERE channel_id = ?
            """ + (forUpdate ? " FOR UPDATE" : ""), (result, rowNum) -> new ChannelFact(
            result.getLong("channel_id"),
            result.getString("channel_code"),
            result.getString("status"),
            result.getInt("revision")
        ), channelId);
        if (rows.isEmpty()) {
            throw new ServiceException(ERROR_CHANNEL_NOT_FOUND + ": " + channelId);
        }
        return rows.getFirst();
    }

    private List<WmsChannelReachabilitySnapshot.MemberFact> loadMembers(
        long channelId,
        boolean forUpdate
    ) {
        return jdbcTemplate.query("""
            SELECT member_id, storage_id, access_order, revision
            FROM wms_storage_access_member
            WHERE channel_id = ?
            ORDER BY access_order, member_id
            """ + (forUpdate ? " FOR UPDATE" : ""),
            (result, rowNum) -> new WmsChannelReachabilitySnapshot.MemberFact(
            result.getLong("member_id"),
            result.getLong("storage_id"),
            result.getInt("access_order"),
            result.getInt("revision")
        ), channelId);
    }

    private List<WmsChannelReachabilitySnapshot.EdgeFact> loadEdges(
        long channelId,
        boolean forUpdate
    ) {
        return jdbcTemplate.query("""
            SELECT e.access_edge_id, e.blocker_storage_id, e.blocked_storage_id, e.revision
            FROM wms_storage_access_edge e
            WHERE EXISTS (
                SELECT 1 FROM wms_storage_access_member b
                WHERE b.channel_id = ? AND b.storage_id = e.blocker_storage_id
            ) OR EXISTS (
                SELECT 1 FROM wms_storage_access_member d
                WHERE d.channel_id = ? AND d.storage_id = e.blocked_storage_id
            )
            ORDER BY e.access_edge_id
            """ + (forUpdate ? " FOR UPDATE" : ""),
            (result, rowNum) -> new WmsChannelReachabilitySnapshot.EdgeFact(
            result.getLong("access_edge_id"),
            result.getLong("blocker_storage_id"),
            result.getLong("blocked_storage_id"),
            result.getInt("revision")
        ), channelId, channelId);
    }

    private List<WmsChannelReachabilitySnapshot.OccupancyFact> loadActiveOccupancies(
        List<WmsChannelReachabilitySnapshot.MemberFact> members,
        boolean forUpdate
    ) {
        if (members.isEmpty()) {
            return List.of();
        }
        Set<Long> storageIds = new LinkedHashSet<>();
        members.forEach(item -> storageIds.add(item.storageId()));
        return namedJdbc.query("""
            SELECT occupancy_id, storage_id, root_node_id, revision
            FROM wms_storage_occupancy
            WHERE status = 'ACTIVE' AND storage_id IN (:storageIds)
            ORDER BY storage_id, occupancy_id
            """ + (forUpdate ? " FOR UPDATE" : ""), Map.of("storageIds", storageIds),
            (result, rowNum) -> new WmsChannelReachabilitySnapshot.OccupancyFact(
                result.getLong("occupancy_id"),
                result.getLong("storage_id"),
                result.getLong("root_node_id"),
                result.getInt("revision")
            ));
    }

    private String snapshotHash(
        ChannelFact channel,
        List<WmsChannelReachabilitySnapshot.MemberFact> members,
        List<WmsChannelReachabilitySnapshot.EdgeFact> edges,
        List<WmsChannelReachabilitySnapshot.OccupancyFact> occupancies
    ) {
        StringBuilder canonical = new StringBuilder(512);
        field(canonical, "channelId", channel.channelId());
        field(canonical, "channelCode", channel.channelCode());
        field(canonical, "status", channel.status());
        field(canonical, "channelRevision", channel.revision());
        for (WmsChannelReachabilitySnapshot.MemberFact member : members) {
            field(canonical, "memberId", member.memberId());
            field(canonical, "storageId", member.storageId());
            field(canonical, "accessOrder", member.accessOrder());
            field(canonical, "memberRevision", member.revision());
        }
        for (WmsChannelReachabilitySnapshot.EdgeFact edge : edges) {
            field(canonical, "accessEdgeId", edge.accessEdgeId());
            field(canonical, "blockerStorageId", edge.blockerStorageId());
            field(canonical, "blockedStorageId", edge.blockedStorageId());
            field(canonical, "edgeRevision", edge.revision());
        }
        for (WmsChannelReachabilitySnapshot.OccupancyFact occupancy : occupancies) {
            field(canonical, "occupancyId", occupancy.occupancyId());
            field(canonical, "occupancyStorageId", occupancy.storageId());
            field(canonical, "rootNodeId", occupancy.rootNodeId());
            field(canonical, "occupancyRevision", occupancy.revision());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM 缺少 SHA-256", error);
        }
    }

    private void field(StringBuilder target, String name, Object value) {
        String text = value == null ? "<NULL>" : String.valueOf(value);
        target.append(name).append('=').append(text.length()).append(':').append(text).append('\n');
    }

    private record ChannelFact(
        long channelId,
        String channelCode,
        String status,
        int revision
    ) {
    }
}
