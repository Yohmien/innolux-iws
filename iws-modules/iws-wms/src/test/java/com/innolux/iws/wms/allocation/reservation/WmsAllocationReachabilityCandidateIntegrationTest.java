/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配候选可达性
 * @Version      : V1.0
 * @Description  : 验证分配评估和锁位使用数据库空间快照硬筛选不可达候选；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.wms.allocation.kernel.AllocationDemand;
import com.innolux.iws.wms.allocation.kernel.AllocationPolicy;
import com.innolux.iws.wms.allocation.kernel.AllocationProtection;
import com.innolux.iws.wms.allocation.kernel.AllocationRequest;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationBusinessContext;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReservationResult;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationReserveCommand;
import com.innolux.iws.wms.allocation.reservation.domain.WmsAllocationSnapshotInput;
import com.innolux.iws.wms.allocation.reservation.service.WmsAllocationReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 分配候选与数据库通道可达性快照的集成测试。 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsAllocationReservationTestContext.class)
@Transactional
class WmsAllocationReachabilityCandidateIntegrationTest {

    private static final long ZONE_ID = 9_801L;

    private static final long CHANNEL_ID = 9_802L;

    private static final long BLOCKER_STORAGE_ID = 9_811L;

    private static final long BLOCKED_STORAGE_ID = 9_812L;

    private static final long FREE_STORAGE_ID = 9_813L;

    private static final long ALLOCATION_UNIT_ID = 9_891L;

    private static final long PLAN_INSTANCE_ID = 9_892L;

    @Autowired
    private WmsAllocationReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
            insert into wms_warehouse_zone
                (zone_id, zone_code, zone_name, zone_kind, warehouse_id)
            values (?, 'Z-REACH-CANDIDATE', '候选可达性测试库区', 'DEVICE', 1)
            """, ZONE_ID);
        jdbcTemplate.update("""
            insert into wms_access_channel
                (channel_id, device_zone_id, channel_code, access_mode, status, revision)
            values (?, ?, 'CH-REACH-CANDIDATE', 'FORK', 'ACTIVE', 1)
            """, CHANNEL_ID, ZONE_ID);
        insertStorage(BLOCKER_STORAGE_ID, "S-REACH-BLOCKER");
        insertStorage(BLOCKED_STORAGE_ID, "S-REACH-BLOCKED");
        insertStorage(FREE_STORAGE_ID, "S-REACH-FREE");
        insertMember(9_821L, BLOCKER_STORAGE_ID, 1);
        insertMember(9_822L, BLOCKED_STORAGE_ID, 2);
        insertMember(9_823L, FREE_STORAGE_ID, 3);
        insertEdge(9_831L, BLOCKER_STORAGE_ID, BLOCKED_STORAGE_ID);
        insertActiveOccupancy(9_841L, 9_851L, BLOCKER_STORAGE_ID, 9_861L, 1);
    }

    @Test
    void reserveRejectsBlockedDescendantUsingLockedReachabilitySnapshot() {
        WmsAllocationReserveCommand command = new WmsAllocationReserveCommand(
            new WmsAllocationBusinessContext(
                "INBOUND_FIXTURE", "入库fixture", 98_001L, "BIZ-REACH-CANDIDATE",
                null, ALLOCATION_UNIT_ID, 1, "TASK-REACH-CANDIDATE", PLAN_INSTANCE_ID
            ),
            "WMS:" + "a".repeat(64),
            "b".repeat(64),
            401L,
            402L,
            "c".repeat(64),
            "d".repeat(64),
            List.of(
                slotInput(BLOCKER_STORAGE_ID, Set.of("A")),
                slotInput(BLOCKED_STORAGE_ID, Set.of()),
                slotInput(FREE_STORAGE_ID, Set.of())
            ),
            new AllocationRequest(
                new AllocationDemand(ALLOCATION_UNIT_ID, Map.of(), 1),
                new AllocationPolicy(List.of())
            )
        );

        WmsAllocationReservationResult result = reservationService.reserve(command);

        assertThat(result.result()).isEqualTo("LOCKED");
        assertThat(result.selectedStorageId()).isEqualTo(FREE_STORAGE_ID);
        assertThat(result.allocationLockIds()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT block_reason
            FROM wms_allocation_candidate
            WHERE decision_id = ? AND storage_id = ?
            """, String.class, result.decisionId(), BLOCKED_STORAGE_ID))
            .contains("显式通道/阻挡路径不可达");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM wms_allocation_candidate
            WHERE decision_id = ? AND storage_id = ? AND selected_flag = 1
            """, Integer.class, result.decisionId(), FREE_STORAGE_ID))
            .isEqualTo(1);
    }

    private void insertStorage(long storageId, String storageCode) {
        jdbcTemplate.update("""
            insert into wms_storage
                (storage_id, storage_code, zone_id, warehouse_id, lock_status,
                 disable_status, version, aisle_text)
            values (?, ?, ?, 1, 0, 0, 0, ?)
            """, storageId, storageCode, ZONE_ID, storageCode);
    }

    private void insertMember(long memberId, long storageId, int accessOrder) {
        jdbcTemplate.update("""
            insert into wms_storage_access_member
                (member_id, channel_id, storage_id, access_order, revision)
            values (?, ?, ?, ?, 1)
            """, memberId, CHANNEL_ID, storageId, accessOrder);
    }

    private void insertEdge(long accessEdgeId, long blockerStorageId, long blockedStorageId) {
        jdbcTemplate.update("""
            insert into wms_storage_access_edge
                (access_edge_id, blocker_storage_id, blocked_storage_id, revision)
            values (?, ?, ?, 1)
            """, accessEdgeId, blockerStorageId, blockedStorageId);
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
            values (?, ?, 'REACHABILITY_CANDIDATE_TEST', 9801, null,
                    'TASK-REACH-CANDIDATE', null, 'APPLIED', '候选可达性测试',
                    current_timestamp(3), 1, 1)
            """, occupancyEffectId, "WMS:%064x".formatted(occupancyEffectId));
        jdbcTemplate.update("""
            insert into wms_storage_occupancy
                (occupancy_id, occupancy_effect_id, storage_id, root_node_id, dimension_key,
                 occupied_qty, unit, generation, status, revision, create_dept, create_by)
            values (?, ?, ?, ?, 'TOP_LEVEL_LOAD', 1, 'LOAD', 1, 'ACTIVE', ?, 1, 1)
            """, occupancyId, occupancyEffectId, storageId, rootNodeId, revision);
    }

    private WmsAllocationSnapshotInput slotInput(long storageId, Set<String> occupiedSlotKeys) {
        return new WmsAllocationSnapshotInput(
            storageId, true, false, true, false, false,
            AllocationProtection.NONE, Map.of(), List.of("A"), occupiedSlotKeys, 0
        );
    }
}
