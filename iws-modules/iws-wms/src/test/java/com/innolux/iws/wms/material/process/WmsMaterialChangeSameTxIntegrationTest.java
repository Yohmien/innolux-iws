/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 SAME_TX 集成测试
 * @Version      : V1.0
 * @Description  : 验证领域事实、trace、checkpoint和平台事件/token同事务及逐点回滚；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.EventMutationService;
import com.innolux.iws.process.runtime.PlanActivationService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.runtime.StateValidator;
import com.innolux.iws.process.runtime.ValidationResult;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeCommand;
import com.innolux.iws.wms.material.change.domain.WmsMaterialChangeResult;
import com.innolux.iws.wms.material.change.service.WmsMaterialChangeService;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;
import com.innolux.iws.wms.material.trace.service.WmsMaterialTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 物料事实 SAME_TX 真实 MySQL 集成测试。
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
@TestPropertySource(properties = "iws.process.enabled=true")
@SpringJUnitConfig(WmsMaterialChangeSameTxTestContext.class)
class WmsMaterialChangeSameTxIntegrationTest {

    private static final long INSTANCE_ID = 88_001L;

    private static final long DEFINITION_VERSION_ID = 12_345L;

    private static final long TOKEN_ID = 77_001L;

    private static final long INVENTORY_ID = 501L;

    private static final long SOURCE_STORAGE_ID = 3_101L;

    private static final long TARGET_STORAGE_ID = 3_102L;

    private static final long ZONE_ID = 2_101L;

    private static final int INITIAL_VERSION = 3;

    private static final String INPUT_HASH = "b".repeat(64);

    private static final String EFFECT_KEY = "WMS:" + INPUT_HASH;

    private static final String TASK_KEY = "material-task-001";

    private static final LocalDateTime OCCURRED_AT =
        LocalDateTime.of(2026, 10, 29, 20, 30, 15, 123_000_000);

    @Autowired
    private EventMutationService mutationService;

    @Autowired
    private WmsMaterialChangeService changeService;

    @Autowired
    private WmsMaterialTraceService traceService;

    @Autowired
    private FixtureMaterialChangeAction applyAction;

    @Autowired
    private FixtureMaterialChangeVerifyAction verifyAction;

    @Autowired
    private RuntimeDao runtimeDao;

    @Autowired
    private PlanActivationService activationService;

    @Autowired
    private com.innolux.iws.process.runtime.CapabilityRegistry capabilityRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessEventMapper eventMapper;

    @Autowired
    private ProcessTokenMapper tokenMapper;

    @BeforeEach
    void resetDatabaseAndRuntimeStubs() {
        jdbcTemplate.update("delete from iws_process_action_audit");
        jdbcTemplate.update("delete from iws_process_attempt");
        jdbcTemplate.update("delete from iws_process_event");
        jdbcTemplate.update("delete from iws_process_schedule_control");
        jdbcTemplate.update("delete from iws_process_token");
        jdbcTemplate.update("delete from iws_process_instance");
        jdbcTemplate.update("delete from wms_material_trace_edge");
        jdbcTemplate.update("delete from wms_material_trace_item");
        jdbcTemplate.update("delete from wms_material_trace_history");
        jdbcTemplate.update("delete from wms_inventory_flow");
        jdbcTemplate.update("delete from wms_inventory");
        jdbcTemplate.update("delete from wms_storage");
        jdbcTemplate.update("delete from wms_warehouse_zone");

        reset(runtimeDao, activationService, capabilityRegistry);
        when(runtimeDao.event(anyLong())).thenAnswer(invocation ->
            eventMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.token(anyLong())).thenAnswer(invocation ->
            tokenMapper.selectById(invocation.getArgument(0))
        );
        when(runtimeDao.systemContext(anyLong(), anyString())).thenReturn(null);
        DefinitionGraph graph = mock(DefinitionGraph.class);
        com.innolux.iws.process.domain.ProcessState state =
            new com.innolux.iws.process.domain.ProcessState();
        state.setValidatorKey("fixture-state");
        state.setValidatorVersion("1");
        when(graph.state(anyString())).thenReturn(state);
        when(runtimeDao.graph(DEFINITION_VERSION_ID)).thenReturn(graph);
        StateValidator validator = mock(StateValidator.class);
        when(validator.validateBusiness(org.mockito.ArgumentMatchers.any()))
            .thenReturn(ValidationResult.pass());
        when(validator.validateStructural(org.mockito.ArgumentMatchers.any()))
            .thenReturn(ValidationResult.pass());
        when(capabilityRegistry.validator("fixture-state", "1")).thenReturn(validator);
    }

    @Test
    void applyThenVerifyCommitsDomainTraceCheckpointAndPlatformRowsTogether() {
        seedZoneAndStorages(0);
        seedInventory("STOCK-501", new BigDecimal("10.123456"), BigDecimal.ZERO, null);
        seedProcess(99_001L, "APPLY_MATERIAL_CHANGE", "CHECKED", "APPLIED", "fixture-success");
        applyAction.prepare(INVENTORY_ID, INITIAL_VERSION, TARGET_STORAGE_ID, OCCURRED_AT, null);

        ProcessAttempt attempt = execute(99_001L, applyAction, "fixture-success");

        assertThat(attempt.getEffectResult().name()).isEqualTo("SUCCEEDED");
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select storage_code from wms_inventory where inventory_id = ?", INVENTORY_ID
        )).isEqualTo("S-TO");
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select version from wms_inventory where inventory_id = ?", INVENTORY_ID
        )).isEqualTo(INITIAL_VERSION + 1L);
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_inventory_flow where idempotency_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_material_trace_history where effect_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_material_trace_history "
                + "where effect_key = ? and plan_instance_id = ? and event_id = ? "
                + "and attempt_id = ? and task_key = ? and effect_result = 'APPLIED'",
            EFFECT_KEY, INSTANCE_ID, 99_001L, attempt.getId(), TASK_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_material_trace_item"
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select checkpoint_json from iws_process_attempt where id = ?", attempt.getId()
        )).contains("\"status\":\"APPLIED\"", "\"traceId\":", "\"flowId\":");
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select event_status from iws_process_event where id = ?", 99_001L
        )).isEqualTo("SUCCESS");
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select state_code from iws_process_token where id = ?", TOKEN_ID
        )).isEqualTo("APPLIED");

        seedEvent(99_002L, "VERIFY_MATERIAL_CHANGE", "APPLIED", "VERIFIED", "fixture-verify");
        verifyAction.prepare(EFFECT_KEY, INVENTORY_ID, TARGET_STORAGE_ID);
        ProcessAttempt verifyAttempt = execute(99_002L, verifyAction, "fixture-verify");

        assertThat(verifyAttempt.getEffectResult().name()).isEqualTo("SUCCEEDED");
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select checkpoint_json from iws_process_attempt where id = ?", verifyAttempt.getId()
        )).contains("\"status\":\"VERIFIED\"");
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select state_code from iws_process_token where id = ?", TOKEN_ID
        )).isEqualTo("VERIFIED");
    }

    @Test
    void traceItemFailureRollsBackDomainTraceCheckpointAndPlatformClosure() {
        seedZoneAndStorages(0);
        seedInventory("__FAIL_ITEM__", new BigDecimal("2.000000"), BigDecimal.ZERO, null);
        seedProcess(99_011L, "APPLY_MATERIAL_CHANGE", "CHECKED", "APPLIED", "fixture-trace-fail");
        applyAction.prepare(INVENTORY_ID, INITIAL_VERSION, TARGET_STORAGE_ID, OCCURRED_AT, null);

        assertThatThrownBy(() -> execute(99_011L, applyAction, "fixture-trace-fail"))
            .isInstanceOf(RuntimeException.class);

        assertNothingCommitted(99_011L);
    }

    @Test
    void checkpointFailureRollsBackDomainTraceAndPlatformResultUpdate() {
        seedZoneAndStorages(0);
        seedInventory("STOCK-501", new BigDecimal("5.000000"), BigDecimal.ZERO, null);
        seedProcess(99_021L, "APPLY_MATERIAL_CHANGE", "CHECKED", "APPLIED", "fixture-checkpoint-fail");
        applyAction.prepare(
            INVENTORY_ID,
            INITIAL_VERSION,
            TARGET_STORAGE_ID,
            OCCURRED_AT,
            "CHECKPOINT"
        );

        assertThatThrownBy(() -> execute(99_021L, applyAction, "fixture-checkpoint-fail"))
            .isInstanceOf(RuntimeException.class);

        assertNothingCommitted(99_021L);
    }

    @Test
    void platformCloseFailureRollsBackEverythingAfterActionAndCheckpointPreparation() {
        seedZoneAndStorages(0);
        seedInventory("STOCK-501", new BigDecimal("6.000000"), BigDecimal.ZERO, null);
        seedProcess(99_031L, "APPLY_MATERIAL_CHANGE", "CHECKED", "APPLIED", "fixture-platform-close");
        applyAction.prepare(INVENTORY_ID, INITIAL_VERSION, TARGET_STORAGE_ID, OCCURRED_AT, null);

        assertThatThrownBy(() -> execute(99_031L, applyAction, "fixture-platform-close"))
            .isInstanceOf(RuntimeException.class);

        assertNothingCommitted(99_031L);
    }

    @Test
    void retryAfterRollbackUsesSameEffectKeyAndAppliesOnlyOnce() {
        seedZoneAndStorages(0);
        seedInventory("__FAIL_ITEM__", new BigDecimal("7.000000"), BigDecimal.ZERO, null);
        WmsMaterialChangeCommand command = command(INITIAL_VERSION);

        assertThatThrownBy(() -> inTransaction(() -> changeService.apply(command)))
            .isInstanceOf(RuntimeException.class);
        jdbcTemplate.update(
            "update wms_inventory set stock_unit_code = 'STOCK-RETRY' where inventory_id = ?",
            INVENTORY_ID
        );

        WmsMaterialChangeResult first = inTransaction(() -> changeService.apply(command));
        WmsMaterialChangeResult replay = inTransaction(() -> changeService.apply(command));

        assertThat(replay.flowId()).isEqualTo(first.flowId());
        assertThat(replay.trace().history().traceId()).isEqualTo(first.trace().history().traceId());
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_inventory_flow where idempotency_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select source_order_type from wms_inventory_flow where idempotency_key = ?", EFFECT_KEY
        )).isEqualTo("MOVE");
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_material_trace_history where effect_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select version from wms_inventory where inventory_id = ?", INVENTORY_ID
        )).isEqualTo(INITIAL_VERSION + 1L);
    }

    @Test
    void concurrentSameEffectKeySerializesOnInventoryAndReplaysCommittedFact() throws Exception {
        seedZoneAndStorages(0);
        seedInventory("STOCK-CONCURRENT", new BigDecimal("3.000000"), BigDecimal.ZERO, null);
        WmsMaterialChangeCommand command = command(INITIAL_VERSION);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = executor.submit(worker(
                () -> inTransaction(() -> changeService.apply(command)), ready, start
            ));
            Future<Outcome> second = executor.submit(worker(
                () -> inTransaction(() -> changeService.apply(command)), ready, start
            ));
            assertThat(ready.await(20L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Outcome firstOutcome = first.get(60L, TimeUnit.SECONDS);
            Outcome secondOutcome = second.get(60L, TimeUnit.SECONDS);
            assertThat(firstOutcome.error()).isNull();
            assertThat(secondOutcome.error()).isNull();
            assertThat(secondOutcome.result().flowId()).isEqualTo(firstOutcome.result().flowId());
            assertThat(secondOutcome.result().trace().history().traceId())
                .isEqualTo(firstOutcome.result().trace().history().traceId());
        } finally {
            executor.shutdownNow();
        }

        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_inventory_flow where idempotency_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_material_trace_history where effect_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select version from wms_inventory where inventory_id = ?", INVENTORY_ID
        )).isEqualTo(INITIAL_VERSION + 1L);
    }

    @Test
    void sameEffectKeyWithDifferentInputHashIsRejectedWithoutSecondEffect() {
        seedZoneAndStorages(0);
        seedInventory("STOCK-CONFLICT", new BigDecimal("4.000000"), BigDecimal.ZERO, null);
        WmsMaterialChangeResult first = inTransaction(() -> changeService.apply(command(INITIAL_VERSION)));

        assertThatThrownBy(() -> inTransaction(() -> changeService.apply(
            new WmsMaterialChangeCommand(
                EFFECT_KEY,
                "c".repeat(64),
                INVENTORY_ID,
                INITIAL_VERSION + 1,
                TARGET_STORAGE_ID,
                OCCURRED_AT.plusMinutes(1),
                "冲突输入",
                "evidence://fixture/conflict",
                "IWS",
                "REQUEST-001",
                "INVENTORY_MOVE_FIXTURE",
                "在库物料位置变更fixture",
                88_001L,
                "BIZ-001",
                null,
                "INVENTORY_MOVE_FIXTURE",
                88_001L,
                TASK_KEY,
                INSTANCE_ID,
                "MATERIAL_CHANGE",
                "MATERIAL_CHANGE",
                String.valueOf(DEFINITION_VERSION_ID),
                99_001L,
                99_100L,
                9_001L,
                9_101L
            )
        ))).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsMaterialChangeService.ERROR_EFFECT_KEY_CONFLICT)
        );

        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_inventory_flow where idempotency_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_material_trace_history where effect_key = ?", EFFECT_KEY
        )).isOne();
        assertThat(first.trace().history().inputHash()).isEqualTo(INPUT_HASH);
    }

    private ProcessAttempt execute(long eventId, com.innolux.iws.process.runtime.LocalAction action,
                                   String owner) {
        EventMutationService.ClaimContext claim = mutationService.claim(eventId, owner, 120_000L);
        assertThat(claim).isNotNull();
        ActionContext context = new ActionContext(
            eventId,
            INSTANCE_ID,
            "E:" + action.describe().key(),
            claim.attempt().getId(),
            "REQUEST-" + eventId,
            EventDirection.INTERNAL,
            PostingMode.AUTOMATIC,
            null,
            action.describe().key().equals(FixtureMaterialChangeAction.ACTION_KEY)
                ? FixtureMaterialChangeAction.LOCAL_EFFECT_KEY
                : FixtureMaterialChangeVerifyAction.LOCAL_EFFECT_KEY,
            "IDEMP-" + eventId,
            INPUT_HASH,
            "{}",
            "evidence://fixture/" + eventId,
            1L,
            120_000L,
            Map.of()
        );
        return mutationService.executeLocalAndFinish(claim, action, context);
    }

    private void assertNothingCommitted(long eventId) {
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select storage_code from wms_inventory where inventory_id = ?", INVENTORY_ID
        )).isEqualTo("S-FROM");
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select version from wms_inventory where inventory_id = ?", INVENTORY_ID
        )).isEqualTo(INITIAL_VERSION);
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_inventory_flow"
        )).isZero();
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from wms_material_trace_history"
        )).isZero();
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select event_status from iws_process_event where id = ?", eventId
        )).isEqualTo("PROCESSING");
        assertThat(WmsMaterialChangeSameTxTestDatabase.string(
            "select checkpoint_status from iws_process_attempt where event_id = ?", eventId
        )).isEqualTo("CLAIMED");
        assertThat(WmsMaterialChangeSameTxTestDatabase.count(
            "select count(*) from iws_process_attempt where event_id = ? and effect_result is not null",
            eventId
        )).isZero();
    }

    private void seedZoneAndStorages(int targetLockStatus) {
        jdbcTemplate.update(
            "insert into wms_warehouse_zone "
                + "(zone_id, zone_code, zone_name, zone_kind, warehouse_id) "
                + "values (?, ?, ?, 'DEVICE', 1)",
            ZONE_ID, "Z-01", "测试库区"
        );
        insertStorage(SOURCE_STORAGE_ID, "S-FROM", 0);
        insertStorage(TARGET_STORAGE_ID, "S-TO", targetLockStatus);
    }

    private void insertStorage(long storageId, String storageCode, int lockStatus) {
        jdbcTemplate.update(
            "insert into wms_storage "
                + "(storage_id, storage_code, zone_id, warehouse_id, lock_status, "
                + "disable_status, version, aisle_text) "
                + "values (?, ?, ?, 1, ?, 0, 0, ?)",
            storageId, storageCode, ZONE_ID, lockStatus, storageCode
        );
    }

    private void seedInventory(String stockUnitCode, BigDecimal quantity,
                               BigDecimal reservedQty, String reservedOrderNo) {
        jdbcTemplate.update(
            "insert into wms_inventory "
                + "(inventory_id, stock_unit_code, material_code, storage_id, storage_code, "
                + "zone_id, zone_code, inventory_qty, reserved_qty, reserved_order_no, unit, version) "
                + "values (?, ?, 'MAT-001', ?, 'S-FROM', ?, 'Z-01', ?, ?, ?, 'EA', ?)",
            INVENTORY_ID,
            stockUnitCode,
            SOURCE_STORAGE_ID,
            ZONE_ID,
            quantity,
            reservedQty,
            reservedOrderNo,
            INITIAL_VERSION
        );
    }

    private void seedProcess(long eventId, String eventCode, String sourceState,
                             String targetState, String ownerSeed) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
            "insert into iws_process_instance "
                + "(id, plan_role, definition_version_id, definition_revision, schedule_id, "
                + "plan_type, business_schedule_code, business_task_key, business_type, business_id, "
                + "task_key, schedule_scope, mode_snapshot, plan_status, input_hash, instance_version, "
                + "create_dept, create_by, create_time, update_time) "
                + "values (?, 'BUSINESS', ?, 1, 10, 'MATERIAL_CHANGE', 'INVENTORY', ?, "
                + "'INVENTORY_MOVE_FIXTURE', '88001', ?, 'ROOT', 'QUEUE', 'RUNNING', ?, 0, "
                + "9101, 9001, ?, ?)",
            INSTANCE_ID,
            DEFINITION_VERSION_ID,
            "INVENTORY_MOVE_FIXTURE:" + TASK_KEY,
            TASK_KEY,
            INPUT_HASH,
            now,
            now
        );
        jdbcTemplate.update(
            "insert into iws_process_token "
                + "(id, instance_id, token_role, token_status, state_code, parent_scope_key, "
                + "scope_key, branch_key, token_version, round_no, create_time, update_time) "
                + "values (?, ?, 'BRANCH', 'BLOCKED', ?, 'ROOT', 'ROOT:START', 'ROOT', 1, 0, ?, ?)",
            TOKEN_ID, INSTANCE_ID, sourceState, now, now
        );
        jdbcTemplate.update(
            "insert into iws_process_schedule_control "
                + "(id, scope_type, scope_key, duty, control_state, revision, wake_pending, "
                + "slot_state, fairness_count, create_time, update_time) "
                + "values (?, 'PLAN_SLOT', ?, 'PLAN_SLOT', 'AUTO_RUNNING', 1, 0, 'HELD', 0, ?, ?)",
            10_001L, String.valueOf(INSTANCE_ID), now, now
        );
        seedEvent(eventId, eventCode, sourceState, targetState, ownerSeed);
    }

    private void seedEvent(long eventId, String eventCode, String sourceState,
                           String targetState, String ownerSeed) {
        LocalDateTime now = LocalDateTime.now();
        String localEffectKey = "APPLY_MATERIAL_CHANGE".equals(eventCode)
            ? FixtureMaterialChangeAction.LOCAL_EFFECT_KEY
            : FixtureMaterialChangeVerifyAction.LOCAL_EFFECT_KEY;
        jdbcTemplate.update(
            "insert into iws_process_event "
                + "(id, instance_id, token_id, definition_version_id, event_definition_id, "
                + "event_code, event_key, branch_identity, event_role, event_direction, system_code, "
                + "local_action_key, local_action_version, local_effect_key, source_state_code, "
                + "target_state_code, event_status, sent, payload_ready, input_hash, idempotency_key, "
                + "execution_purpose, priority, retry_count, lease_epoch, event_version, "
                + "recovery_requested, create_time, update_time) "
                + "values (?, ?, ?, ?, 1, ?, ?, 'ROOT', 'BUSINESS', 'INTERNAL', 'IWS', ?, '1', ?, "
                + "?, ?, 'PENDING', 0, 1, ?, ?, 'NORMAL', 0, 0, 0, 1, 0, ?, ?)",
            eventId,
            INSTANCE_ID,
            TOKEN_ID,
            DEFINITION_VERSION_ID,
            eventCode,
            "E:" + eventCode,
            eventCode,
            localEffectKey,
            sourceState,
            targetState,
            INPUT_HASH,
            "IDEMP-" + eventId,
            now,
            now
        );
    }

    private WmsMaterialChangeCommand command(int expectedVersion) {
        return new WmsMaterialChangeCommand(
            EFFECT_KEY,
            INPUT_HASH,
            INVENTORY_ID,
            expectedVersion,
            TARGET_STORAGE_ID,
            OCCURRED_AT,
            "测试物料位置事实",
            "evidence://fixture/apply",
            "IWS",
            "REQUEST-001",
            "INVENTORY_MOVE_FIXTURE",
            "在库物料位置变更fixture",
            88_001L,
            "BIZ-001",
            null,
            "INVENTORY_MOVE_FIXTURE",
            88_001L,
            TASK_KEY,
            INSTANCE_ID,
            "MATERIAL_CHANGE",
            "MATERIAL_CHANGE",
            String.valueOf(DEFINITION_VERSION_ID),
            99_001L,
            99_100L,
            9_001L,
            9_101L
        );
    }

    private <T> T inTransaction(Callable<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return Objects.requireNonNull(template.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }));
    }

    private Callable<Outcome> worker(Callable<WmsMaterialChangeResult> action,
                                     CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await(20L, TimeUnit.SECONDS);
            try {
                return new Outcome(action.call(), null);
            } catch (Throwable error) {
                return new Outcome(null, error);
            }
        };
    }

    private record Outcome(WmsMaterialChangeResult result, Throwable error) {
    }
}
