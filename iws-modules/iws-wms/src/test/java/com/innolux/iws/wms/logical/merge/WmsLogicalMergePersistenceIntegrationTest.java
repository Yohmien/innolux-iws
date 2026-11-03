/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 在隔离 MySQL 验证归属切换、来源 CAS、同事务历史、批次幂等和异摘要拒绝；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeResult;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeRetireCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeRetireResult;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeStartCommand;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergeOrchestrationService;
import com.innolux.iws.wms.logical.merge.process.WmsLogicalMergePlanContract;
import com.innolux.iws.wms.logical.merge.service.WmsLogicalMergeService;
import com.innolux.iws.wms.logical.merge.service.impl.WmsLogicalMergeServiceImpl;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.service.WmsBusinessTaskBarrierService;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import com.innolux.iws.wms.task.service.impl.WmsBusinessTaskBarrierServiceImpl;
import com.innolux.iws.wms.task.service.impl.WmsBusinessTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("local")
class WmsLogicalMergePersistenceIntegrationTest {

    private static final String DATABASE_NAME = "iws_wms_logical_merge_test";

    private static final String DATABASE_USER = "iws_wms_logical_merge_test";

    private static final String DATABASE_PASSWORD = "iws_wms_logical_merge_test";

    private static final Pattern TARGET_SCHEMA_LINE =
        Pattern.compile("(?m)^SET @target_schema = '[^']+';$");

    private static final long TARGET_SCOPE_VERSION = 900L;

    private static final long TARGET_PACKAGE_VERSION = 700L;

    private static final String SPACE_DIGEST = "a".repeat(64);

    private static final MySQLContainer MYSQL = startContainer();

    private static final DriverManagerDataSource DATA_SOURCE = dataSource();

    private static final JdbcTemplate JDBC = new JdbcTemplate(DATA_SOURCE);

    private static final WmsLogicalMergeService SERVICE = new WmsLogicalMergeServiceImpl(
        JDBC,
        new IncrementingIdentifierGenerator()
    );

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final IdentifierGenerator IDS = new IncrementingIdentifierGenerator();

    private static final WmsBusinessTaskService TASKS =
        new WmsBusinessTaskServiceImpl(JDBC, IDS, JSON);

    private static final WmsBusinessTaskBarrierService BARRIERS =
        new WmsBusinessTaskBarrierServiceImpl(JDBC, IDS, JSON);

    private static final WmsLogicalMergeOrchestrationService ORCHESTRATION =
        new WmsLogicalMergeOrchestrationService(
            JDBC, JSON, SERVICE, TASKS, BARRIERS);

    private static final TransactionTemplate TRANSACTION =
        new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));

    static {
        initializeSchema();
    }

    @BeforeEach
    void resetAndSeed() {
        JDBC.update("delete from wms_logical_merge_batch");
        JDBC.update("delete from wms_logical_merge_order");
        JDBC.update("delete from wms_business_task_barrier");
        JDBC.update("delete from wms_business_task");
        JDBC.update("delete from wms_logical_assignment_history");
        JDBC.update("delete from wms_command_attempt where action = 'LOGICAL_MERGE'");
        JDBC.update("delete from wms_material_logical_assignment");
        JDBC.update("delete from wms_logical_scope_storage");
        JDBC.update("delete from wms_logical_scope_version");
        JDBC.update("delete from wms_logical_scope");
        JDBC.update("delete from wms_rule_effective_pointer");
        JDBC.update("delete from wms_rule_package_member");
        JDBC.update("delete from wms_rule_package_version");
        seedFixtures();
    }

    @Test
    void migratesCompatibleAssignmentsWritesHistoryAndReplaysSameEffect() {
        WmsLogicalMergeCommand command = command("WMS:" + "1".repeat(64), 50);

        WmsLogicalMergeResult first = inTransaction(() -> SERVICE.apply(command));

        assertThat(first.replayed()).isFalse();
        assertThat(first.migratedCount()).isEqualTo(2);
        assertThat(first.alreadyTargetCount()).isZero();
        assertThat(first.conflictRetainedCount()).isEqualTo(1);
        assertThat(first.migratedAssignments())
            .extracting(WmsLogicalMergeResult.MigratedAssignment::inventoryId)
            .containsExactly(1L, 2L);
        assertThat(scopeOf(1L)).isEqualTo(TARGET_SCOPE_VERSION);
        assertThat(scopeOf(2L)).isEqualTo(TARGET_SCOPE_VERSION);
        assertThat(scopeOf(3L)).isEqualTo(300L);
        assertThat(revisionOf(1L)).isEqualTo(2);
        assertThat(revisionOf(2L)).isEqualTo(3);
        assertThat(revisionOf(3L)).isEqualTo(1);
        assertThat(count("wms_logical_assignment_history")).isEqualTo(2);
        assertThat(attemptCount()).isEqualTo(1);
        assertThat(versionStatus(100L)).isEqualTo("ACTIVE");
        assertThat(versionStatus(200L)).isEqualTo("ACTIVE");
        assertThat(versionStatus(300L)).isEqualTo("ACTIVE");
        assertThat(versionStatus(TARGET_SCOPE_VERSION)).isEqualTo("ACTIVE");
        assertThat(scopeLifecycle(100L)).isEqualTo("RETIRING");
        assertThat(scopeLifecycle(900L)).isEqualTo("ACTIVE");

        WmsLogicalMergeResult replay = inTransaction(() -> SERVICE.apply(command));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.inputDigest()).isEqualTo(first.inputDigest());
        assertThat(replay.migratedAssignments())
            .containsExactlyElementsOf(first.migratedAssignments());
        assertThat(count("wms_logical_assignment_history")).isEqualTo(2);
        assertThat(attemptCount()).isEqualTo(1);
    }

    @Test
    void persistentBatchBarrierClosesParentAndRetiresSourcesInOrder() {
        List<WmsLogicalMergePlanner.Assignment> assignments = List.of(
            assignment(1L, 10L, 100L, TARGET_PACKAGE_VERSION, 1),
            assignment(2L, 20L, 200L, 600L, 2)
        );
        WmsLogicalMergeCommand batchCommand = new WmsLogicalMergeCommand(
            "WMS:" + "9".repeat(64),
            target(),
            Set.of(100L, 200L),
            Set.of(200L),
            50,
            assignments,
            "P6-BATCH-1",
            null,
            LocalDateTime.of(2026, 11, 2, 11, 0),
            "P6固定批次",
            9001L,
            "tester",
            9100L,
            "WMS"
        );
        WmsLogicalMergeStartCommand start = new WmsLogicalMergeStartCommand(
            "P6-ORDER-1",
            TARGET_SCOPE_VERSION,
            Set.of(100L, 200L),
            Set.of(200L),
            50,
            "P6父单"
        );
        String startJson = JSON.writeValueAsString(start);
        String batchJson = JSON.writeValueAsString(batchCommand);
        WmsBusinessTaskSnapshot parentTask = TASKS.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                WmsLogicalMergePlanContract.BUSINESS_TYPE,
                88001L,
                null,
                88001L,
                0,
                "CLOSE",
                "P6-ORDER-1",
                WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
                7001L,
                sha256(startJson),
                startJson,
                9001L,
                9100L
            )
        );
        WmsBusinessTaskSnapshot childTask = TASKS.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                WmsLogicalMergePlanContract.BUSINESS_TYPE,
                88001L,
                88002L,
                88001L,
                1,
                "APPLY",
                "P6-TASK-1",
                WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
                7002L,
                sha256(batchJson),
                batchJson,
                9001L,
                9100L
            )
        );
        JDBC.update("""
            insert into wms_logical_merge_order
                (merge_order_id, command_key, target_scope_version_id,
                 source_scope_version_ids_json, compatible_scope_version_ids_json,
                 batch_size, input_hash, input_json, status, expected_batch_count,
                 conflict_count, revision, parent_plan_instance_id,
                 parent_business_task_id, reason, create_time, update_time)
            values (88001, 'P6-ORDER-1', ?, '[100,200]', '[200]', 50, ?,
                    ?, 'RUNNING', 1, 0, 1, 7001, ?, 'P6父单',
                    current_timestamp(3), current_timestamp(3))
            """, TARGET_SCOPE_VERSION, sha256(startJson), startJson, parentTask.taskId());
        JDBC.update("""
            insert into wms_logical_merge_batch
                (batch_id, merge_order_id, batch_no, task_key, plan_instance_id,
                 business_task_id, input_hash, input_json, status, migrated_count,
                 conflict_retained_count, revision, create_time, update_time)
            values (88002, 88001, 1, 'P6-TASK-1', 7002, ?, ?, ?,
                    'CREATED', 0, 0, 1, current_timestamp(3), current_timestamp(3))
            """, childTask.taskId(), sha256(batchJson), batchJson);
        var barrier = BARRIERS.createOrReplay(new WmsBusinessTaskBarrierCreateCommand(
            WmsLogicalMergePlanContract.BUSINESS_TYPE,
            88001L,
            WmsLogicalMergePlanContract.BARRIER_KEY,
            List.of("P6-TASK-1"),
            9001L,
            9100L
        ));
        JDBC.update(
            "update wms_logical_merge_order set barrier_id = ? where merge_order_id = 88001",
            barrier.barrierId());

        inTransaction(() -> ORCHESTRATION.captureParent(88001L));
        assertThatThrownBy(() -> inTransaction(() ->
            ORCHESTRATION.closeParent(88001L)
        )).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).contains("BUSINESS_TASK_BARRIER_BLOCKED"));

        inTransaction(() -> ORCHESTRATION.captureBatch(88002L));
        inTransaction(() -> ORCHESTRATION.checkBatch(88002L));
        inTransaction(() -> ORCHESTRATION.applyBatch(88002L));
        inTransaction(() -> ORCHESTRATION.closeBatch(88002L));
        assertThat(JDBC.queryForObject(
            "select status from wms_business_task where task_id = ?",
            String.class, childTask.taskId()
        )).isEqualTo("COMPLETED");
        assertThat(JDBC.queryForObject(
            "select status from wms_logical_scope_version where scope_version_id = 200",
            String.class
        )).isEqualTo("ACTIVE");

        inTransaction(() -> ORCHESTRATION.closeParent(88001L));

        assertThat(JDBC.queryForObject(
            "select status from wms_logical_merge_order where merge_order_id = 88001",
            String.class
        )).isEqualTo("COMPLETED");
        assertThat(JDBC.queryForObject(
            "select status from wms_business_task_barrier where barrier_id = ?",
            String.class, barrier.barrierId()
        )).isEqualTo("COMPLETED");
        assertThat(JDBC.queryForObject(
            "select status from wms_business_task where task_id = ?",
            String.class, parentTask.taskId()
        )).isEqualTo("COMPLETED");
        assertThat(JDBC.queryForObject(
            "select status from wms_logical_scope_version where scope_version_id = 200",
            String.class
        )).isEqualTo("RETIRED");
    }

    @Test
    void rejectsSameEffectWithDifferentInputDigest() {
        WmsLogicalMergeCommand first = command("WMS:" + "2".repeat(64), 50);
        inTransaction(() -> SERVICE.apply(first));

        WmsLogicalMergeCommand different = command(
            first.effectKey(),
            List.of(defaultAssignments().getFirst())
        );

        assertThatThrownBy(() -> inTransaction(() -> SERVICE.apply(different)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsLogicalMergeService.ERROR_EFFECT_CONFLICT));
        assertThat(count("wms_logical_assignment_history")).isEqualTo(2);
        assertThat(attemptCount()).isEqualTo(1);
    }

    @Test
    void rejectsMoreThanOnePersistentBatchBeforeAnyWrite() {
        WmsLogicalMergeCommand oversized = command(
            "WMS:" + "9".repeat(64),
            defaultAssignments(),
            2
        );

        assertThatThrownBy(() -> inTransaction(() -> SERVICE.apply(oversized)))
            .isInstanceOfSatisfying(ServiceException.class, error -> {
                assertThat(error.getMessage()).startsWith(
                    WmsLogicalMergeService.ERROR_BATCH_EXCEEDS_LIMIT);
                assertThat(error.getCode()).isEqualTo(400);
            });
        assertThat(scopeOf(1L)).isEqualTo(100L);
        assertThat(scopeOf(2L)).isEqualTo(200L);
        assertThat(versionStatus(100L)).isEqualTo("ACTIVE");
        assertThat(count("wms_logical_assignment_history")).isZero();
        assertThat(attemptCount()).isZero();
    }

    @Test
    void retiresOnlyDrainedSourcesAndReplaysRetirement() {
        WmsLogicalMergeCommand merge = command("WMS:" + "a".repeat(64), 50);
        inTransaction(() -> SERVICE.apply(merge));
        WmsLogicalMergeRetireCommand retire = retireCommand(
            "WMS:" + "b".repeat(64),
            Set.of(100L, 200L)
        );

        WmsLogicalMergeRetireResult first = inTransaction(
            () -> SERVICE.retireSources(retire));
        WmsLogicalMergeRetireResult replay = inTransaction(
            () -> SERVICE.retireSources(retire));

        assertThat(first.replayed()).isFalse();
        assertThat(first.retiredScopeVersionIds()).containsExactly(100L, 200L);
        assertThat(first.retiredScopeIds()).containsExactly(100L, 200L);
        assertThat(replay.replayed()).isTrue();
        assertThat(versionStatus(100L)).isEqualTo("RETIRED");
        assertThat(versionStatus(200L)).isEqualTo("RETIRED");
        assertThat(scopeLifecycle(100L)).isEqualTo("RETIRED");
        assertThat(scopeLifecycle(200L)).isEqualTo("RETIRED");
        assertThat(versionStatus(300L)).isEqualTo("ACTIVE");
        assertThat(scopeLifecycle(300L)).isEqualTo("RETIRING");

        assertThatThrownBy(() -> inTransaction(() -> SERVICE.retireSources(
            retireCommand("WMS:" + "c".repeat(64), Set.of(300L))
        ))).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(
                WmsLogicalMergeService.ERROR_SOURCE_NOT_DRAINED)
        );
    }

    @Test
    void rejectsStaleRevisionOrScopeAndLeavesNoPartialBatch() {
        WmsLogicalMergeCommand staleRevision = command(
            "WMS:" + "3".repeat(64),
            List.of(
                assignment(1L, 10L, 100L, TARGET_PACKAGE_VERSION, 2),
                assignment(2L, 20L, 200L, 600L, 2),
                assignment(3L, 30L, 300L, 500L, 1)
            )
        );

        assertThatThrownBy(() -> inTransaction(() -> SERVICE.apply(staleRevision)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsLogicalMergeService.ERROR_ASSIGNMENT_CONFLICT));

        WmsLogicalMergeCommand staleScope = command(
            "WMS:" + "4".repeat(64),
            List.of(
                assignment(1L, 10L, 100L, TARGET_PACKAGE_VERSION, 1),
                assignment(2L, 20L, 300L, 500L, 2),
                assignment(3L, 30L, 300L, 500L, 1)
            )
        );

        assertThatThrownBy(() -> inTransaction(() -> SERVICE.apply(staleScope)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsLogicalMergeService.ERROR_ASSIGNMENT_CONFLICT));

        assertThat(scopeOf(1L)).isEqualTo(100L);
        assertThat(scopeOf(2L)).isEqualTo(200L);
        assertThat(count("wms_logical_assignment_history")).isZero();
        assertThat(attemptCount()).isZero();
    }

    @Test
    void rejectsChangedTargetFactsBeforeAnyWrite() {
        WmsLogicalMergeCommand changedTarget = new WmsLogicalMergeCommand(
            "WMS:" + "5".repeat(64),
            new WmsLogicalMergePlanner.TargetScope(
                TARGET_SCOPE_VERSION,
                TARGET_PACKAGE_VERSION,
                "ACTIVE",
                TARGET_SCOPE_VERSION,
                "b".repeat(64)
            ),
            Set.of(100L, 200L, 300L),
            Set.of(200L),
            50,
            defaultAssignments(),
            "TASK-P6-01",
            501L,
            LocalDateTime.of(2026, 11, 1, 10, 0),
            "P6逻辑归属合并",
            9001L,
            "tester",
            9100L,
            "WMS"
        );

        assertThatThrownBy(() -> inTransaction(() -> SERVICE.apply(changedTarget)))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsLogicalMergeService.ERROR_TARGET_CHANGED));
        assertThat(count("wms_logical_assignment_history")).isZero();
        assertThat(attemptCount()).isZero();
    }

    @Test
    void rollsBackAssignmentBatchAndAttemptWhenHistoryWriteFails() {
        executeAsRoot("""
            create trigger trg_wms_logical_merge_rollback
            before insert on wms_logical_assignment_history
            for each row
            begin
              if new.inventory_id = 2 then
                signal sqlstate '45000' set message_text = 'forced history failure';
              end if;
            end
            """);
        try {
            WmsLogicalMergeCommand command = command("WMS:" + "6".repeat(64), 50);

            assertThatThrownBy(() -> inTransaction(() -> SERVICE.apply(command)))
                .isInstanceOf(RuntimeException.class);
        } finally {
            executeAsRoot("drop trigger if exists trg_wms_logical_merge_rollback");
        }

        assertThat(scopeOf(1L)).isEqualTo(100L);
        assertThat(scopeOf(2L)).isEqualTo(200L);
        assertThat(revisionOf(1L)).isEqualTo(1);
        assertThat(count("wms_logical_assignment_history")).isZero();
        assertThat(attemptCount()).isZero();
    }

    @Test
    void concurrentDifferentEffectsCommitOnlyOneStableBatch() throws Exception {
        WmsLogicalMergeCommand first = command("WMS:" + "7".repeat(64), 50);
        WmsLogicalMergeCommand second = command("WMS:" + "8".repeat(64), 50);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> left = executor.submit(() -> runConcurrently(first, ready, start));
            Future<Outcome> right = executor.submit(() -> runConcurrently(second, ready, start));
            ready.await();
            start.countDown();

            List<Outcome> outcomes = List.of(left.get(), right.get());
            assertThat(outcomes).filteredOn(outcome -> outcome.result() != null).hasSize(1);
            assertThat(outcomes)
                .filteredOn(outcome -> outcome.error() != null)
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.error().getMessage())
                    .matches(message -> message.startsWith(
                        WmsLogicalMergeService.ERROR_ASSIGNMENT_CONFLICT)
                        || message.startsWith(WmsLogicalMergeService.ERROR_CONCURRENCY_RETRY)));
        } finally {
            executor.shutdownNow();
        }

        assertThat(scopeOf(1L)).isEqualTo(TARGET_SCOPE_VERSION);
        assertThat(scopeOf(2L)).isEqualTo(TARGET_SCOPE_VERSION);
        assertThat(revisionOf(1L)).isEqualTo(2);
        assertThat(revisionOf(2L)).isEqualTo(3);
        assertThat(count("wms_logical_assignment_history")).isEqualTo(2);
        assertThat(attemptCount()).isEqualTo(1);
    }

    private static Outcome runConcurrently(
        WmsLogicalMergeCommand command,
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new Outcome(inTransaction(() -> SERVICE.apply(command)), null);
        } catch (RuntimeException error) {
            return new Outcome(null, error);
        }
    }

    private static void executeAsRoot(String sql) {
        try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", DATABASE_PASSWORD
        ); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException error) {
            throw new IllegalStateException("隔离回滚触发器执行失败", error);
        }
    }

    private static WmsLogicalMergeCommand command(String effectKey, int batchSize) {
        return command(effectKey, defaultAssignments(), batchSize);
    }

    private static WmsLogicalMergeCommand command(
        String effectKey,
        List<WmsLogicalMergePlanner.Assignment> assignments
    ) {
        return command(effectKey, assignments, 50);
    }

    private static WmsLogicalMergeRetireCommand retireCommand(
        String effectKey,
        Set<Long> sourceIds
    ) {
        return new WmsLogicalMergeRetireCommand(
            effectKey,
            sourceIds,
            "TASK-P6-RETIRE",
            502L,
            LocalDateTime.of(2026, 11, 2, 10, 0),
            "P6来源退役",
            9001L,
            "tester",
            9100L,
            "WMS"
        );
    }

    private static WmsLogicalMergeCommand command(
        String effectKey,
        List<WmsLogicalMergePlanner.Assignment> assignments,
        int batchSize
    ) {
        return new WmsLogicalMergeCommand(
            effectKey,
            target(),
            Set.of(100L, 200L, 300L),
            Set.of(200L),
            batchSize,
            assignments,
            "TASK-P6-01",
            501L,
            LocalDateTime.of(2026, 11, 1, 10, 0),
            "P6逻辑归属合并",
            9001L,
            "tester",
            9100L,
            "WMS"
        );
    }

    private static WmsLogicalMergePlanner.TargetScope target() {
        return new WmsLogicalMergePlanner.TargetScope(
            TARGET_SCOPE_VERSION,
            TARGET_PACKAGE_VERSION,
            "ACTIVE",
            TARGET_SCOPE_VERSION,
            SPACE_DIGEST
        );
    }

    private static List<WmsLogicalMergePlanner.Assignment> defaultAssignments() {
        return List.of(
            assignment(1L, 10L, 100L, TARGET_PACKAGE_VERSION, 1),
            assignment(2L, 20L, 200L, 600L, 2),
            assignment(3L, 30L, 300L, 500L, 1)
        );
    }

    private static WmsLogicalMergePlanner.Assignment assignment(
        long inventoryId,
        long assignmentId,
        long scopeVersionId,
        long rulePackageVersionId,
        int revision
    ) {
        return new WmsLogicalMergePlanner.Assignment(
            inventoryId,
            assignmentId,
            scopeVersionId,
            rulePackageVersionId,
            revision
        );
    }

    private static <T> T inTransaction(Supplier<T> action) {
        return TRANSACTION.execute(status -> action.get());
    }

    private static String sha256(String value) {
        return WmsRuleDigest.sha256(value);
    }

    private static int scopeOf(long inventoryId) {
        return JDBC.queryForObject("""
            select scope_version_id
              from wms_material_logical_assignment
             where inventory_id = ?
            """, Integer.class, inventoryId);
    }

    private static int revisionOf(long inventoryId) {
        return JDBC.queryForObject("""
            select assignment_revision
              from wms_material_logical_assignment
             where inventory_id = ?
            """, Integer.class, inventoryId);
    }

    private static String versionStatus(long scopeVersionId) {
        return JDBC.queryForObject("""
            select status from wms_logical_scope_version
             where scope_version_id = ?
            """, String.class, scopeVersionId);
    }

    private static String scopeLifecycle(long scopeId) {
        return JDBC.queryForObject("""
            select lifecycle_status from wms_logical_scope
             where logical_scope_id = ?
            """, String.class, scopeId);
    }

    private static int attemptCount() {
        return JDBC.queryForObject("""
            select count(*) from wms_command_attempt where action = 'LOGICAL_MERGE'
            """, Integer.class);
    }

    private static int count(String table) {
        return JDBC.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void seedFixtures() {
        insertPackage(500L, "P6-OLD", 500);
        insertPackage(600L, "P6-COMPATIBLE", 600);
        insertPackage(TARGET_PACKAGE_VERSION, "P6-TARGET", 700);
        insertScope(100L, "P6-SOURCE-SAME", 100L);
        insertScope(200L, "P6-SOURCE-COMPATIBLE", 200L);
        insertScope(300L, "P6-SOURCE-BLOCKED", 300L);
        insertScope(900L, "P6-TARGET-SCOPE", TARGET_SCOPE_VERSION);
        insertScopeVersion(100L, 100L, TARGET_PACKAGE_VERSION);
        insertScopeVersion(200L, 200L, 600L);
        insertScopeVersion(300L, 300L, 500L);
        insertScopeVersion(900L, TARGET_SCOPE_VERSION, TARGET_PACKAGE_VERSION);
        JDBC.update("""
            insert into wms_material_logical_assignment (
                logical_assignment_id, inventory_id, scope_version_id,
                assignment_revision, source_business_type, source_business_id, revision
            ) values
              (10, 1, 100, 1, 'MATERIAL', 1, 1),
              (20, 2, 200, 2, 'MATERIAL', 2, 1),
              (30, 3, 300, 1, 'MATERIAL', 3, 1)
            """);
    }

    private static void insertPackage(long packageVersionId, String code, int versionNo) {
        JDBC.update("""
            insert into wms_rule_package_version (
                package_version_id, package_code, version_no, canonical_digest,
                status, published_at, published_by
            ) values (?, ?, ?, ?, 'ACTIVE', current_timestamp(3), 1)
            """, packageVersionId, code, versionNo, "c".repeat(64));
    }

    private static void insertScope(long scopeId, String code, long currentVersionId) {
        JDBC.update("""
            insert into wms_logical_scope (
                logical_scope_id, scope_code, scope_name,
                lifecycle_status, current_version_id, revision
            ) values (?, ?, ?, 'ACTIVE', ?, 1)
            """, scopeId, code, code, currentVersionId);
    }

    private static void insertScopeVersion(
        long logicalScopeId,
        long scopeVersionId,
        long packageVersionId
    ) {
        JDBC.update("""
            insert into wms_logical_scope_version (
                scope_version_id, logical_scope_id, version_no,
                rule_package_version_id, space_snapshot_digest, status,
                published_at, published_by
            ) values (?, ?, 1, ?, ?, 'ACTIVE', current_timestamp(3), 1)
            """,
            scopeVersionId,
            logicalScopeId,
            packageVersionId,
            scopeVersionId == TARGET_SCOPE_VERSION ? SPACE_DIGEST : "d".repeat(64)
        );
    }

    private static MySQLContainer startContainer() {
        MySQLContainer container = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD)
            .withEnv("MYSQL_ROOT_PASSWORD", DATABASE_PASSWORD)
            .withEnv("MYSQL_ROOT_HOST", "%");
        try {
            container.start();
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "逻辑归属合并集成测试需要可用的 Docker 与 mysql:8.0.45 镜像",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(
            new Thread(container::stop, "wms-logical-merge-test-mysql-stop")
        );
        return container;
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(jdbcUrl());
        source.setUsername(DATABASE_USER);
        source.setPassword(DATABASE_PASSWORD);
        return source;
    }

    private static String jdbcUrl() {
        String url = MYSQL.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator
            + "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    }

    private static void initializeSchema() {
        grantMetadataVisibility();
        try (Connection connection = DriverManager.getConnection(
            jdbcUrl(), DATABASE_USER, DATABASE_PASSWORD
        )) {
            ScriptUtils.executeSqlScript(
                connection,
                new FileSystemResource(scriptPath("script/sql/iws_wms_smt_core.sql"))
            );
            String rules = Files.readString(
                scriptPath("script/sql/iws_inventory_management_rules.sql"),
                StandardCharsets.UTF_8
            );
            String scoped = TARGET_SCHEMA_LINE.matcher(rules).replaceFirst(
                "SET @target_schema = '" + DATABASE_NAME + "';"
            );
            for (int execution = 0; execution < 2; execution++) {
                ScriptUtils.executeSqlScript(
                    connection,
                    new ByteArrayResource(scoped.getBytes(StandardCharsets.UTF_8))
                );
            }
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("逻辑归属合并隔离结构初始化失败", error);
        }
    }

    private static void grantMetadataVisibility() {
        try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", DATABASE_PASSWORD
        ); Statement statement = connection.createStatement()) {
            statement.execute(
                "grant select, trigger on *.* to '" + DATABASE_USER + "'@'%'"
            );
        } catch (SQLException error) {
            throw new IllegalStateException("逻辑归属合并隔离验收账号元数据授权失败", error);
        }
    }

    private static Path scriptPath(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth <= 6 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "找不到项目脚本: " + relativePath + "，当前工作目录 "
                + System.getProperty("user.dir")
        );
    }

    private static final class IncrementingIdentifierGenerator implements IdentifierGenerator {

        private final AtomicLong next = new AtomicLong(100_000L);

        @Override
        public Number nextId(Object entity) {
            return next.incrementAndGet();
        }
    }

    private record Outcome(WmsLogicalMergeResult result, RuntimeException error) {
    }
}
