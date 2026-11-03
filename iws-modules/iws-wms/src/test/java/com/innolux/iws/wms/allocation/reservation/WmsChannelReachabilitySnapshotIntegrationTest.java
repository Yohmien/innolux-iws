/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道可达性
 * @Version      : V1.0
 * @Description  : 验证真实 MySQL 通道成员、阻挡边、活动占用和规范摘要快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilityEvaluator;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilitySnapshot;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilitySnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通道可达性只读快照隔离测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationReservationTestContext.class)
class WmsChannelReachabilitySnapshotIntegrationTest {

    private static final long ZONE_ID = 9_601L;

    private static final long CHANNEL_ID = 9_602L;

    private static final long OTHER_CHANNEL_ID = 9_603L;

    @Autowired
    private WmsChannelReachabilitySnapshotService snapshotService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from wms_storage_occupancy");
        jdbcTemplate.update("delete from wms_occupancy_effect");
        jdbcTemplate.update("delete from wms_storage_access_edge");
        jdbcTemplate.update("delete from wms_storage_access_member");
        jdbcTemplate.update("delete from wms_access_channel");
        jdbcTemplate.update("delete from wms_storage");
        jdbcTemplate.update("delete from wms_warehouse_zone");
        jdbcTemplate.update("""
            insert into wms_warehouse_zone
                (zone_id, zone_code, zone_name, zone_kind, warehouse_id)
            values (?, 'Z-REACHABILITY', '可达性测试库区', 'DEVICE', 1)
            """, ZONE_ID);
        insertChannel(CHANNEL_ID, "CH-REACHABILITY");
        insertChannel(OTHER_CHANNEL_ID, "CH-REACHABILITY-OTHER");
        insertStorage(9_611L, "S-REACH-1");
        insertStorage(9_612L, "S-REACH-2");
        insertStorage(9_613L, "S-REACH-3");
        insertStorage(9_614L, "S-REACH-4");
    }

    @Test
    void snapshotUsesExplicitOrderingEdgesAndActiveOccupancy() {
        insertMember(9_621L, CHANNEL_ID, 9_611L, 30, 2);
        insertMember(9_622L, CHANNEL_ID, 9_612L, 10, 3);
        insertMember(9_623L, CHANNEL_ID, 9_613L, 20, 4);
        insertEdge(9_631L, 9_612L, 9_613L, 5);
        insertEdge(9_632L, 9_613L, 9_611L, 6);
        insertActiveOccupancy(9_641L, 9_651L, 9_612L, 9_671L, 7);

        WmsChannelReachabilitySnapshot snapshot = snapshotService.snapshot(CHANNEL_ID);

        assertThat(snapshot.channelCode()).isEqualTo("CH-REACHABILITY");
        assertThat(snapshot.channelRevision()).isEqualTo(1);
        assertThat(snapshot.members()).extracting(WmsChannelReachabilitySnapshot.MemberFact::storageId)
            .containsExactly(9_612L, 9_613L, 9_611L);
        assertThat(snapshot.edges()).extracting(WmsChannelReachabilitySnapshot.EdgeFact::accessEdgeId)
            .containsExactly(9_631L, 9_632L);
        assertThat(snapshot.activeOccupancies()).extracting(
            WmsChannelReachabilitySnapshot.OccupancyFact::occupancyId)
            .containsExactly(9_641L);
        assertThat(snapshot.reachability().reachableStorageIds()).containsExactly(9_612L);
        assertThat(snapshot.reachability().unreachableStorageIds()).containsExactly(9_613L, 9_611L);
        assertThat(snapshot.reachability().occupiedBlockers(9_611L)).containsExactly(9_612L);
        assertThat(snapshot.snapshotHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void occupancyChangeChangesReachabilityAndSnapshotHash() {
        insertMember(9_621L, CHANNEL_ID, 9_611L, 1, 1);
        insertMember(9_622L, CHANNEL_ID, 9_612L, 2, 1);
        insertEdge(9_631L, 9_611L, 9_612L, 1);

        WmsChannelReachabilitySnapshot before = snapshotService.snapshot(CHANNEL_ID);
        assertThat(before.reachability().reachableStorageIds()).containsExactly(9_611L, 9_612L);

        insertActiveOccupancy(9_641L, 9_651L, 9_611L, 9_671L, 1);
        WmsChannelReachabilitySnapshot after = snapshotService.snapshot(CHANNEL_ID);

        assertThat(after.reachability().reachableStorageIds()).containsExactly(9_611L);
        assertThat(after.reachability().unreachableStorageIds()).containsExactly(9_612L);
        assertThat(after.reachability().occupiedBlockers(9_612L)).containsExactly(9_611L);
        assertThat(after.snapshotHash()).isNotEqualTo(before.snapshotHash());
    }

    @Test
    void crossChannelEdgeIsRejectedRatherThanIgnored() {
        insertMember(9_621L, CHANNEL_ID, 9_611L, 1, 1);
        insertMember(9_622L, OTHER_CHANNEL_ID, 9_614L, 1, 1);
        insertEdge(9_631L, 9_611L, 9_614L, 1);

        assertThatThrownBy(() -> snapshotService.snapshot(CHANNEL_ID))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_GRAPH)
            .hasMessageContaining("不属于当前通道");
    }

    @Test
    void unknownChannelIsRejected() {
        assertThatThrownBy(() -> snapshotService.snapshot(999_999L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilitySnapshotService.ERROR_CHANNEL_NOT_FOUND);
    }

    private void insertChannel(long channelId, String channelCode) {
        jdbcTemplate.update("""
            insert into wms_access_channel
                (channel_id, device_zone_id, channel_code, access_mode, status, revision)
            values (?, ?, ?, 'FORK', 'ACTIVE', 1)
            """, channelId, ZONE_ID, channelCode);
    }

    private void insertStorage(long storageId, String storageCode) {
        jdbcTemplate.update("""
            insert into wms_storage
                (storage_id, storage_code, zone_id, warehouse_id, lock_status,
                 disable_status, version, aisle_text)
            values (?, ?, ?, 1, 0, 0, 0, ?)
            """, storageId, storageCode, ZONE_ID, storageCode);
    }

    private void insertMember(
        long memberId,
        long channelId,
        long storageId,
        int accessOrder,
        int revision
    ) {
        jdbcTemplate.update("""
            insert into wms_storage_access_member
                (member_id, channel_id, storage_id, access_order, revision)
            values (?, ?, ?, ?, ?)
            """, memberId, channelId, storageId, accessOrder, revision);
    }

    private void insertEdge(
        long accessEdgeId,
        long blockerStorageId,
        long blockedStorageId,
        int revision
    ) {
        jdbcTemplate.update("""
            insert into wms_storage_access_edge
                (access_edge_id, blocker_storage_id, blocked_storage_id, revision)
            values (?, ?, ?, ?)
            """, accessEdgeId, blockerStorageId, blockedStorageId, revision);
    }

    private void insertActiveOccupancy(
        long occupancyId,
        long occupancyEffectId,
        long storageId,
        long rootNodeId,
        int revision
    ) {
        jdbcTemplate.update("""
            insert into wms_occupancy_effect
                (occupancy_effect_id, effect_key, business_type, business_id, business_line_id,
                 task_key, plan_instance_id, effect_result, reason, occurred_at,
                 create_dept, create_by)
            values (?, ?, 'REACHABILITY_TEST', 9601, null, 'TASK-REACHABILITY', null,
                    'APPLIED', '可达性测试', current_timestamp(3), 1, 1)
            """, occupancyEffectId, "WMS:%064x".formatted(occupancyEffectId));
        jdbcTemplate.update("""
            insert into wms_storage_occupancy
                (occupancy_id, occupancy_effect_id, storage_id, root_node_id, dimension_key,
                 occupied_qty, unit, generation, status, revision, create_dept, create_by)
            values (?, ?, ?, ?, 'TOP_LEVEL_LOAD', 1, 'LOAD', 1, 'ACTIVE', ?, 1, 1)
            """, occupancyId, occupancyEffectId, storageId, rootNodeId, revision);
    }
}
