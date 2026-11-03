/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 集成测试
 * @Version      : V1.0
 * @Description  : 验证追加幂等、并发唯一、事务回滚、精度、补偿和快照不漂移；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace;

import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceAppendCommand;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceEdgeInput;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceItemInput;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceItemRef;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceKind;
import com.innolux.iws.wms.material.trace.domain.WmsMaterialTraceSnapshot;
import com.innolux.iws.wms.material.trace.service.WmsMaterialTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 物料 trace 追加与读取的真实 MySQL 集成测试。
 *
 * @author YONGJIE.PENG
 */
@Tag("local")
@SpringJUnitConfig(WmsMaterialTraceTestContext.class)
class WmsMaterialTraceIntegrationTest {

    private static final String EFFECT_KEY = "WMS:" + "a".repeat(64);

    private static final String INPUT_HASH = "b".repeat(64);

    private static final LocalDateTime OCCURRED_AT =
        LocalDateTime.of(2026, 10, 29, 14, 30, 15, 123_000_000);

    @Autowired
    private WmsMaterialTraceService traceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("delete from wms_material_trace_edge");
        jdbcTemplate.update("delete from wms_material_trace_item");
        jdbcTemplate.update("delete from wms_material_trace_history");
        jdbcTemplate.update("delete from wms_material_trace_current_name_fixture");
    }

    @Test
    void appendWritesCompleteSnapshotAndReadDoesNotRehydrateRequest() {
        JsonNode production = jsonMapper.readTree("""
            {"materialName":"原始料名","batch":"B-01","attributes":{"msd":"3"}}
            """);
        WmsMaterialTraceAppendCommand command = command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "原父业务名",
            "原操作者",
            List.of(
                item("SOURCE", 501L, "MAT-A", "STOCK-A", "EA", decimal("10.123456"), production),
                item("TARGET", 502L, null, "STOCK-B", "EA", decimal("2.500000"), null)
            ),
            List.of(edge("SOURCE", "TARGET", decimal("1.250000"), "EA", 1))
        );

        WmsMaterialTraceSnapshot written = append(command);

        assertThat(written.history().traceId()).isPositive();
        assertThat(written.history().effectKey()).isEqualTo(EFFECT_KEY);
        assertThat(written.history().businessType()).isEqualTo("INVENTORY_MOVE");
        assertThat(written.history().businessName()).isEqualTo("原父业务名");
        assertThat(written.history().businessId()).isEqualTo(88001L);
        assertThat(written.history().businessNo()).isEqualTo("BIZ-001");
        assertThat(written.history().businessLineId()).isEqualTo(88002L);
        assertThat(written.history().rootBusinessType()).isEqualTo("INBOUND_ORDER");
        assertThat(written.history().rootBusinessId()).isEqualTo(88003L);
        assertThat(written.history().taskKey()).isEqualTo("p88001:l88002:u88004:r1:MATERIAL");
        assertThat(written.history().planInstanceId()).isEqualTo(88005L);
        assertThat(written.history().planCode()).isEqualTo("MATERIAL_CHANGE");
        assertThat(written.history().planType()).isEqualTo("MATERIAL_CHANGE");
        assertThat(written.history().definitionVersion()).isEqualTo("v3");
        assertThat(written.history().eventId()).isEqualTo(88006L);
        assertThat(written.history().attemptId()).isEqualTo(88007L);
        assertThat(written.history().inputHash()).isEqualTo(INPUT_HASH);
        assertThat(written.history().reverseTraceId()).isNull();
        assertThat(written.history().traceKind()).isEqualTo(WmsMaterialTraceKind.FACT);
        assertThat(written.history().effectResult()).isEqualTo("APPLIED");
        assertThat(written.history().reason()).isEqualTo("测试物料事实");
        assertThat(written.history().evidenceRef()).isEqualTo("evidence://fixture/01");
        assertThat(written.history().actorId()).isEqualTo(9001L);
        assertThat(written.history().actorName()).isEqualTo("原操作者");
        assertThat(written.history().initiatorId()).isEqualTo("initiator-77");
        assertThat(written.history().initiatorName()).isEqualTo("原发起人");
        assertThat(written.history().departmentId()).isEqualTo(9101L);
        assertThat(written.history().departmentName()).isEqualTo("原部门名");
        assertThat(written.history().executionSubject()).isEqualTo("HUMAN");
        assertThat(written.history().executionAccount()).isEqualTo("operator-account");
        assertThat(written.history().onBehalfOf()).isEqualTo("delegate-01");
        assertThat(written.history().sourceSystem()).isEqualTo("IWS");
        assertThat(written.history().terminalId()).isEqualTo("TERMINAL-01");
        assertThat(written.history().requestId()).isEqualTo("REQUEST-01");
        assertThat(written.history().sourceTraceId()).isEqualTo("SOURCE-TRACE-01");
        assertThat(written.history().occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(written.history().recordedAt()).isNotNull();
        assertThat(written.history().createBy()).isEqualTo(9001L);
        assertThat(written.history().createDept()).isEqualTo(9101L);
        assertThat(written.history().createTime()).isNotNull();
        assertThat(written.history().updateBy()).isNull();
        assertThat(written.history().updateTime()).isNull();

        assertThat(written.items()).hasSize(2);
        assertThat(written.items().get(0)).satisfies(item -> {
            assertThat(item.itemId()).isPositive();
            assertThat(item.traceId()).isEqualTo(written.history().traceId());
            assertThat(item.inventoryId()).isEqualTo(501L);
            assertThat(item.stockUnitCode()).isEqualTo("STOCK-A");
            assertThat(item.materialCode()).isEqualTo("MAT-A");
            assertThat(item.productionInfoId()).isEqualTo(7001L);
            assertThat(item.productionRevision()).isEqualTo(4);
            assertThat(item.productionSnapshot()).isEqualTo(production);
            assertThat(item.quantity()).isEqualByComparingTo("10.123456");
            assertThat(item.unit()).isEqualTo("EA");
            assertThat(item.beforeQty()).isEqualByComparingTo("8.000000");
            assertThat(item.deltaQty()).isEqualByComparingTo("2.123456");
            assertThat(item.afterQty()).isEqualByComparingTo("10.123456");
            assertThat(item.physicalFromStorageId()).isEqualTo(3101L);
            assertThat(item.physicalFromStorageCode()).isEqualTo("S-FROM");
            assertThat(item.physicalToStorageId()).isEqualTo(3102L);
            assertThat(item.physicalToStorageCode()).isEqualTo("S-TO");
            assertThat(item.logicalFromScopeVersionId()).isEqualTo(3201L);
            assertThat(item.logicalToScopeVersionId()).isEqualTo(3202L);
            assertThat(item.loadParentBeforeId()).isEqualTo(3301L);
            assertThat(item.loadParentAfterId()).isEqualTo(3302L);
            assertThat(item.loadRootBeforeId()).isEqualTo(3401L);
            assertThat(item.loadRootAfterId()).isEqualTo(3402L);
            assertThat(item.lifecycleBefore()).isEqualTo("ACTIVE");
            assertThat(item.lifecycleAfter()).isEqualTo("ACTIVE");
            assertThat(item.rulePackageVersionId()).isEqualTo(3501L);
            assertThat(item.recordedAt()).isNotNull();
            assertThat(item.createBy()).isEqualTo(9001L);
            assertThat(item.createDept()).isEqualTo(9101L);
            assertThat(item.createTime()).isNotNull();
            assertThat(item.updateBy()).isNull();
            assertThat(item.updateTime()).isNull();
        });
        assertThat(written.items().get(1).materialCode()).isNull();

        assertThat(written.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.traceEdgeId()).isPositive();
            assertThat(edge.traceId()).isEqualTo(written.history().traceId());
            assertThat(edge.sourceItemId()).isEqualTo(written.items().get(0).itemId());
            assertThat(edge.targetItemId()).isEqualTo(written.items().get(1).itemId());
            assertThat(edge.quantity()).isEqualByComparingTo("1.250000");
            assertThat(edge.unit()).isEqualTo("EA");
            assertThat(edge.groupNo()).isEqualTo(1);
            assertThat(edge.recordedAt()).isNotNull();
            assertThat(edge.createBy()).isEqualTo(9001L);
            assertThat(edge.createDept()).isEqualTo(9101L);
            assertThat(edge.createTime()).isNotNull();
            assertThat(edge.updateBy()).isNull();
            assertThat(edge.updateTime()).isNull();
        });

        assertThat(traceService.findByEffectKey(EFFECT_KEY)).contains(written);
        assertThat(countRows("wms_material_trace_history", "1 = 1")).isOne();
        assertThat(countRows("wms_material_trace_item", "1 = 1")).isEqualTo(2L);
        assertThat(countRows("wms_material_trace_edge", "1 = 1")).isOne();
        assertThat(countRows("wms_inventory", "1 = 1")).isZero();
        assertThat(columnCount("wms_material_trace_history", "committed_at")).isZero();
    }

    @Test
    void sameEffectKeyAndHashReturnsPersistedFact() {
        WmsMaterialTraceAppendCommand original = command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "首次父业务名",
            "首次操作者",
            List.of(item("A", 601L, "OLD-MAT", "STOCK-OLD", "EA", decimal("1.000000"), null)),
            List.of()
        );
        WmsMaterialTraceSnapshot first = append(original);
        WmsMaterialTraceAppendCommand changedRequest = command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "重放时不应覆盖",
            "重放时操作者",
            List.of(item("B", 999L, "NEW-MAT", "STOCK-NEW", "BOX", decimal("9.000000"), null)),
            List.of()
        );

        WmsMaterialTraceSnapshot replay = append(changedRequest);

        assertThat(replay).isEqualTo(first);
        assertThat(replay.history().businessName()).isEqualTo("首次父业务名");
        assertThat(replay.items()).singleElement()
            .satisfies(item -> assertThat(item.materialCode()).isEqualTo("OLD-MAT"));
        assertThat(countRows("wms_material_trace_history", "effect_key = ?", EFFECT_KEY)).isOne();
    }

    @Test
    void sameEffectKeyWithDifferentHashReturnsStableConflict() {
        append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "首次父业务名",
            "首次操作者",
            List.of(item("A", 602L, "MAT-A", "STOCK-A", "EA", decimal("1"), null)),
            List.of()
        ));

        assertThatThrownBy(() -> append(command(
            EFFECT_KEY,
            "c".repeat(64),
            WmsMaterialTraceKind.FACT,
            null,
            "冲突父业务名",
            "冲突操作者",
            List.of(item("A", 602L, "MAT-A", "STOCK-A", "EA", decimal("1"), null)),
            List.of()
        ))).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getCode()).isEqualTo(HttpStatus.HTTP_CONFLICT);
            assertThat(error.getMessage()).startsWith(WmsMaterialTraceService.ERROR_EFFECT_KEY_CONFLICT);
        });
        assertThat(countRows("wms_material_trace_history", "effect_key = ?", EFFECT_KEY)).isOne();
    }

    @Test
    void concurrentSameEffectKeyCreatesOnlyOneFact() throws Exception {
        WmsMaterialTraceAppendCommand command = command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "并发父业务名",
            "并发操作者",
            List.of(item("A", 603L, "MAT-A", "STOCK-A", "EA", decimal("1"), null)),
            List.of()
        );

        List<ExecutionOutcome> outcomes = runConcurrently(
            () -> append(command),
            () -> append(command)
        );

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(outcomes).extracting(outcome -> outcome.snapshot().history().traceId())
            .containsOnly(outcomes.get(0).snapshot().history().traceId());
        assertThat(countRows("wms_material_trace_history", "effect_key = ?", EFFECT_KEY)).isOne();
    }

    @Test
    void concurrentDifferentHashDoesNotSwallowFailureAsSuccess() throws Exception {
        WmsMaterialTraceAppendCommand first = command(
            EFFECT_KEY,
            "d".repeat(64),
            WmsMaterialTraceKind.FACT,
            null,
            "并发摘要甲",
            "操作者甲",
            List.of(item("A", 604L, "MAT-A", "STOCK-A", "EA", decimal("1"), null)),
            List.of()
        );
        WmsMaterialTraceAppendCommand second = command(
            EFFECT_KEY,
            "e".repeat(64),
            WmsMaterialTraceKind.FACT,
            null,
            "并发摘要乙",
            "操作者乙",
            List.of(item("A", 605L, "MAT-B", "STOCK-B", "EA", decimal("2"), null)),
            List.of()
        );

        List<ExecutionOutcome> outcomes = runConcurrently(
            () -> append(first),
            () -> append(second)
        );

        assertThat(outcomes).filteredOn(outcome -> outcome.error() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.error() != null).singleElement()
            .satisfies(outcome -> {
                assertThat(outcome.error()).isInstanceOf(ServiceException.class);
                ServiceException error = (ServiceException) outcome.error();
                assertThat(error.getCode()).isEqualTo(HttpStatus.HTTP_CONFLICT);
                assertThat(error.getMessage())
                    .startsWith(WmsMaterialTraceService.ERROR_EFFECT_KEY_CONFLICT);
            });
        assertThat(countRows("wms_material_trace_history", "effect_key = ?", EFFECT_KEY)).isOne();
    }

    @Test
    void itemInsertFailureAfterHeadRollsBackWholeFact() {
        WmsMaterialTraceAppendCommand command = command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(item("A", 606L, "MAT-A", "__FAIL_ITEM__", "EA", decimal("1"), null)),
            List.of()
        );

        assertThatThrownBy(() -> append(command)).isInstanceOf(RuntimeException.class);
        assertThat(countRows("wms_material_trace_history", "1 = 1")).isZero();
        assertThat(countRows("wms_material_trace_item", "1 = 1")).isZero();
    }

    @Test
    void edgeInsertFailureAfterHeadAndItemsRollsBackWholeFact() {
        WmsMaterialTraceAppendCommand command = command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(
                item("A", 607L, "MAT-A", "STOCK-A", "EA", decimal("1"), null),
                item("B", 608L, "MAT-B", "STOCK-B", "EA", decimal("1"), null)
            ),
            List.of(edge("A", "B", decimal("1"), "EA", 9999))
        );

        assertThatThrownBy(() -> append(command)).isInstanceOf(RuntimeException.class);
        assertThat(countRows("wms_material_trace_history", "1 = 1")).isZero();
        assertThat(countRows("wms_material_trace_item", "1 = 1")).isZero();
        assertThat(countRows("wms_material_trace_edge", "1 = 1")).isZero();
    }

    @Test
    void callerRollbackRemovesHeadItemsAndEdges() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            traceService.append(command(
                EFFECT_KEY,
                INPUT_HASH,
                WmsMaterialTraceKind.FACT,
                null,
                "父业务名",
                "操作者",
                List.of(
                    item("A", 609L, "MAT-A", "STOCK-A", "EA", decimal("1"), null),
                    item("B", 610L, "MAT-B", "STOCK-B", "EA", decimal("1"), null)
                ),
                List.of(edge("A", "B", decimal("1"), "EA", 1))
            ));
            status.setRollbackOnly();
        });

        assertThat(countRows("wms_material_trace_history", "1 = 1")).isZero();
        assertThat(countRows("wms_material_trace_item", "1 = 1")).isZero();
        assertThat(countRows("wms_material_trace_edge", "1 = 1")).isZero();
    }

    @Test
    void appendRequiresCallerExistingTransaction() {
        assertThatThrownBy(() -> traceService.append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(item("A", 611L, "MAT-A", "STOCK-A", "EA", decimal("1"), null)),
            List.of()
        ))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countRows("wms_material_trace_history", "1 = 1")).isZero();
    }

    @Test
    void rejectsOriginalBigDecimalScaleAndIntegerOverflowWithoutRounding() {
        assertThatThrownBy(() -> append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(item("A", 612L, "MAT-A", "STOCK-A", "EA", decimal("1.0000000"), null)),
            List.of()
        ))).isInstanceOfSatisfying(ServiceException.class, error -> {
            assertThat(error.getCode()).isEqualTo(HttpStatus.HTTP_BAD_REQUEST);
            assertThat(error.getMessage()).startsWith(WmsMaterialTraceService.ERROR_QUANTITY_INVALID);
        });

        assertThatThrownBy(() -> append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(item(
                "A",
                613L,
                "MAT-A",
                "STOCK-A",
                "EA",
                decimal("100000000000000"),
                null
            )),
            List.of()
        ))).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsMaterialTraceService.ERROR_QUANTITY_INVALID)
        );
        assertThat(countRows("wms_material_trace_history", "1 = 1")).isZero();
    }

    @Test
    void rejectsSelfCycleAndFactReferenceToOriginalItem() {
        assertThatThrownBy(() -> append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(
                item("A", 618L, "MAT-A", "STOCK-A", "EA", decimal("1"), null),
                item("B", 619L, "MAT-B", "STOCK-B", "EA", decimal("1"), null),
                item("C", 620L, "MAT-C", "STOCK-C", "EA", decimal("1"), null)
            ),
            List.of(
                edge("A", "B", decimal("1"), "EA", 1),
                edge("B", "C", decimal("1"), "EA", 1),
                edge("C", "A", decimal("1"), "EA", 1)
            )
        ))).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsMaterialTraceService.ERROR_EDGE_INVALID)
        );

        assertThatThrownBy(() -> append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(item("A", 621L, "MAT-A", "STOCK-A", "EA", decimal("1"), null)),
            List.of(new WmsMaterialTraceEdgeInput(
                WmsMaterialTraceItemRef.current("A"),
                WmsMaterialTraceItemRef.current("A"),
                decimal("1"),
                "EA",
                1
            ))
        ))).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsMaterialTraceService.ERROR_EDGE_INVALID)
        );

        assertThatThrownBy(() -> append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "父业务名",
            "操作者",
            List.of(item("A", 622L, "MAT-A", "STOCK-A", "EA", decimal("1"), null)),
            List.of(new WmsMaterialTraceEdgeInput(
                WmsMaterialTraceItemRef.original(999_999L),
                WmsMaterialTraceItemRef.current("A"),
                decimal("1"),
                "EA",
                1
            ))
        ))).isInstanceOfSatisfying(ServiceException.class, error ->
            assertThat(error.getMessage()).startsWith(WmsMaterialTraceService.ERROR_EDGE_INVALID)
        );
        assertThat(countRows("wms_material_trace_history", "1 = 1")).isZero();
    }

    @Test
    void compensationAppendsNewTraceWithoutUpdatingOriginalFact() {
        WmsMaterialTraceSnapshot fact = append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "原父业务名",
            "原操作者",
            List.of(
                item("A", 614L, "MAT-A", "STOCK-A", "EA", decimal("3.000000"), null),
                item("B", 615L, "MAT-B", "STOCK-B", "EA", decimal("3.000000"), null)
            ),
            List.of(edge("A", "B", decimal("3.000000"), "EA", 1))
        ));
        String compensationEffectKey = "WMS:" + "f".repeat(64);
        WmsMaterialTraceAppendCommand compensation = command(
            compensationEffectKey,
            "1".repeat(64),
            WmsMaterialTraceKind.COMPENSATION,
            fact.history().traceId(),
            "补偿父业务名",
            "补偿操作者",
            List.of(item("RESTORE", 616L, "MAT-A", "STOCK-C", "EA", decimal("3.000000"), null)),
            List.of(new WmsMaterialTraceEdgeInput(
                WmsMaterialTraceItemRef.original(fact.items().get(0).itemId()),
                WmsMaterialTraceItemRef.current("RESTORE"),
                decimal("3.000000"),
                "EA",
                2
            ))
        );

        WmsMaterialTraceSnapshot compensationSnapshot = append(compensation);

        assertThat(compensationSnapshot.history().traceKind())
            .isEqualTo(WmsMaterialTraceKind.COMPENSATION);
        assertThat(compensationSnapshot.history().reverseTraceId())
            .isEqualTo(fact.history().traceId());
        assertThat(compensationSnapshot.history().effectResult()).isEqualTo("APPLIED");
        assertThat(compensationSnapshot.history().traceId()).isNotEqualTo(fact.history().traceId());
        assertThat(traceService.findByEffectKey(EFFECT_KEY)).contains(fact);
        assertThat(countRows("wms_material_trace_history", "1 = 1")).isEqualTo(2L);
        assertThat(countRows("wms_material_trace_item", "trace_id = ?", fact.history().traceId()))
            .isEqualTo(2L);
        assertThat(countRows("wms_material_trace_edge", "trace_id = ?", fact.history().traceId()))
            .isOne();
        assertThat(countRows("wms_material_trace_edge", "trace_id = ?",
            compensationSnapshot.history().traceId())).isOne();
    }

    @Test
    void storedNamesDoNotDriftWhenCurrentSourceNamesChange() {
        jdbcTemplate.update(
            "insert into wms_material_trace_current_name_fixture (fixture_id, display_name) "
                + "values (1, '旧当前名称')"
        );
        JsonNode production = jsonMapper.readTree("""
            {"materialName":"旧生产名称"}
            """);
        WmsMaterialTraceSnapshot written = append(command(
            EFFECT_KEY,
            INPUT_HASH,
            WmsMaterialTraceKind.FACT,
            null,
            "旧父业务快照",
            "旧操作者快照",
            List.of(item("A", 617L, "MAT-A", "STOCK-A", "EA", decimal("1"), production)),
            List.of()
        ));

        jdbcTemplate.update(
            "update wms_material_trace_current_name_fixture set display_name = '新当前名称' "
                + "where fixture_id = 1"
        );
        WmsMaterialTraceSnapshot read = traceService.findByEffectKey(EFFECT_KEY).orElseThrow();

        assertThat(read).isEqualTo(written);
        assertThat(read.history().businessName()).isEqualTo("旧父业务快照");
        assertThat(read.history().actorName()).isEqualTo("旧操作者快照");
        assertThat(read.items()).singleElement().satisfies(item ->
            assertThat(item.productionSnapshot().path("materialName").stringValue())
                .isEqualTo("旧生产名称")
        );
    }

    private WmsMaterialTraceSnapshot append(WmsMaterialTraceAppendCommand command) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return Objects.requireNonNull(transaction.execute(status -> traceService.append(command)));
    }

    private WmsMaterialTraceAppendCommand command(
        String effectKey,
        String inputHash,
        WmsMaterialTraceKind traceKind,
        Long reverseTraceId,
        String businessName,
        String actorName,
        List<WmsMaterialTraceItemInput> items,
        List<WmsMaterialTraceEdgeInput> edges
    ) {
        return new WmsMaterialTraceAppendCommand(
            effectKey,
            "INVENTORY_MOVE",
            businessName,
            88001L,
            "BIZ-001",
            88002L,
            "INBOUND_ORDER",
            88003L,
            "p88001:l88002:u88004:r1:MATERIAL",
            88005L,
            "MATERIAL_CHANGE",
            "MATERIAL_CHANGE",
            "v3",
            88006L,
            88007L,
            inputHash,
            reverseTraceId,
            traceKind,
            "测试物料事实",
            "evidence://fixture/01",
            9001L,
            actorName,
            "initiator-77",
            "原发起人",
            9101L,
            "原部门名",
            "HUMAN",
            "operator-account",
            "delegate-01",
            "IWS",
            "TERMINAL-01",
            "REQUEST-01",
            "SOURCE-TRACE-01",
            OCCURRED_AT,
            items,
            edges
        );
    }

    private WmsMaterialTraceItemInput item(
        String itemRef,
        long inventoryId,
        String materialCode,
        String stockUnitCode,
        String unit,
        BigDecimal quantity,
        JsonNode productionSnapshot
    ) {
        return new WmsMaterialTraceItemInput(
            itemRef,
            inventoryId,
            stockUnitCode,
            materialCode,
            7001L,
            4,
            productionSnapshot,
            quantity,
            unit,
            decimal("8.000000"),
            decimal("2.123456"),
            quantity,
            3101L,
            "S-FROM",
            3102L,
            "S-TO",
            3201L,
            3202L,
            3301L,
            3302L,
            3401L,
            3402L,
            "ACTIVE",
            "ACTIVE",
            3501L
        );
    }

    private WmsMaterialTraceEdgeInput edge(
        String sourceRef,
        String targetRef,
        BigDecimal quantity,
        String unit,
        int groupNo
    ) {
        return new WmsMaterialTraceEdgeInput(
            WmsMaterialTraceItemRef.current(sourceRef),
            WmsMaterialTraceItemRef.current(targetRef),
            quantity,
            unit,
            groupNo
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private long countRows(String table, String where, Object... args) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + where,
            Long.class,
            args
        );
        return Objects.requireNonNull(count);
    }

    private long columnCount(String table, String column) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns "
                + "where table_schema = database() and table_name = ? and column_name = ?",
            Long.class,
            table,
            column
        );
        return Objects.requireNonNull(count);
    }

    private List<ExecutionOutcome> runConcurrently(
        Callable<WmsMaterialTraceSnapshot> first,
        Callable<WmsMaterialTraceSnapshot> second
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ExecutionOutcome> firstResult = executor.submit(
                worker(first, ready, start)
            );
            Future<ExecutionOutcome> secondResult = executor.submit(
                worker(second, ready, start)
            );
            assertThat(ready.await(20L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ExecutionOutcome> outcomes = new ArrayList<>();
            outcomes.add(firstResult.get(60L, TimeUnit.SECONDS));
            outcomes.add(secondResult.get(60L, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<ExecutionOutcome> worker(
        Callable<WmsMaterialTraceSnapshot> action,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return new ExecutionOutcome(action.call(), null);
            } catch (Throwable error) {
                return new ExecutionOutcome(null, error);
            }
        };
    }

    private record ExecutionOutcome(WmsMaterialTraceSnapshot snapshot, Throwable error) {
    }
}
