/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 验证固定范围、兼容筛选、冲突保留和稳定合批；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge;

import com.innolux.iws.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("local")
class WmsLogicalMergePlannerTest {

    private static final long TARGET_SCOPE = 900L;

    private static final long TARGET_PACKAGE = 700L;

    private static final String SPACE_DIGEST = "a".repeat(64);

    @Test
    void migratesSamePackageAndExplicitCompatibleSourceButRetainsOthers() {
        WmsLogicalMergePlanner.Plan plan = WmsLogicalMergePlanner.plan(command(
            50,
            Set.of(100L, 200L, 300L),
            Set.of(200L),
            List.of(
                assignment(4L, 40L, 400L, 800L, 1),
                assignment(1L, 10L, 100L, TARGET_PACKAGE, 1),
                assignment(2L, 20L, 200L, 600L, 1),
                assignment(3L, 30L, 300L, 500L, 1)
            )
        ));

        assertThat(plan.decisions())
            .extracting(
                WmsLogicalMergePlanner.Decision::inventoryId,
                WmsLogicalMergePlanner.Decision::action,
                WmsLogicalMergePlanner.Decision::reason
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                    1L, WmsLogicalMergePlanner.Action.MIGRATE,
                    WmsLogicalMergePlanner.Reason.COMPATIBLE),
                org.assertj.core.groups.Tuple.tuple(
                    2L, WmsLogicalMergePlanner.Action.MIGRATE,
                    WmsLogicalMergePlanner.Reason.COMPATIBLE),
                org.assertj.core.groups.Tuple.tuple(
                    3L, WmsLogicalMergePlanner.Action.CONFLICT_RETAINED,
                    WmsLogicalMergePlanner.Reason.RULE_INCOMPATIBLE),
                org.assertj.core.groups.Tuple.tuple(
                    4L, WmsLogicalMergePlanner.Action.CONFLICT_RETAINED,
                    WmsLogicalMergePlanner.Reason.SOURCE_OUT_OF_SCOPE)
            );
    }

    @Test
    void alreadyTargetIsSkippedWithoutReclassification() {
        WmsLogicalMergePlanner.Plan plan = WmsLogicalMergePlanner.plan(command(
            50,
            Set.of(100L),
            Set.of(),
            List.of(assignment(1L, 10L, TARGET_SCOPE, 100L, 2))
        ));

        assertThat(plan.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.action()).isEqualTo(WmsLogicalMergePlanner.Action.ALREADY_TARGET);
            assertThat(decision.reason()).isEqualTo(WmsLogicalMergePlanner.Reason.TARGET_SCOPE);
        });
    }

    @Test
    void inputOrderDoesNotChangeDigestDecisionsOrBatches() {
        WmsLogicalMergePlanner.Command first = command(
            2,
            Set.of(300L, 100L, 200L),
            Set.of(200L),
            List.of(
                assignment(3L, 30L, 300L, 900L, 1),
                assignment(1L, 10L, 100L, TARGET_PACKAGE, 1),
                assignment(2L, 20L, 200L, 800L, 1)
            )
        );
        WmsLogicalMergePlanner.Command second = command(
            2,
            Set.of(100L, 300L, 200L),
            Set.of(200L),
            List.of(
                assignment(2L, 20L, 200L, 800L, 1),
                assignment(3L, 30L, 300L, 900L, 1),
                assignment(1L, 10L, 100L, TARGET_PACKAGE, 1)
            )
        );

        WmsLogicalMergePlanner.Plan left = WmsLogicalMergePlanner.plan(first);
        WmsLogicalMergePlanner.Plan right = WmsLogicalMergePlanner.plan(second);

        assertThat(left.digest()).isEqualTo(right.digest()).matches("[0-9a-f]{64}");
        assertThat(left.decisions()).isEqualTo(right.decisions());
        assertThat(left.batches()).containsExactly(
            new WmsLogicalMergePlanner.Batch(1, left.decisions().subList(0, 2)),
            new WmsLogicalMergePlanner.Batch(2, left.decisions().subList(2, 3))
        );
    }

    @Test
    void rejectsNonEffectiveTarget() {
        WmsLogicalMergePlanner.TargetScope draft = new WmsLogicalMergePlanner.TargetScope(
            TARGET_SCOPE, TARGET_PACKAGE, "DRAFT", TARGET_SCOPE, SPACE_DIGEST);
        WmsLogicalMergePlanner.TargetScope stale = new WmsLogicalMergePlanner.TargetScope(
            TARGET_SCOPE, TARGET_PACKAGE, "ACTIVE", TARGET_SCOPE + 1, SPACE_DIGEST);

        assertThatThrownBy(() -> WmsLogicalMergePlanner.plan(command(
            draft, 50, Set.of(100L), Set.of(), List.of(
                assignment(1L, 10L, 100L, TARGET_PACKAGE, 1)))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsLogicalMergePlanner.ERROR_TARGET_NOT_EFFECTIVE));
        assertThatThrownBy(() -> WmsLogicalMergePlanner.plan(command(
            stale, 50, Set.of(100L), Set.of(), List.of(
                assignment(1L, 10L, 100L, TARGET_PACKAGE, 1)))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsLogicalMergePlanner.ERROR_TARGET_NOT_EFFECTIVE));
    }

    @Test
    void rejectsDuplicateInventoryAndEmptyRange() {
        assertThatThrownBy(() -> WmsLogicalMergePlanner.plan(command(
            50,
            Set.of(100L),
            Set.of(),
            List.of(
                assignment(1L, 10L, 100L, TARGET_PACKAGE, 1),
                assignment(1L, 11L, 100L, TARGET_PACKAGE, 1)
            )
        )))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).contains("重复物料"));
        assertThatThrownBy(() -> WmsLogicalMergePlanner.plan(command(
            50, Set.of(), Set.of(), List.of(assignment(1L, 10L, 100L, TARGET_PACKAGE, 1)))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage())
                    .startsWith(WmsLogicalMergePlanner.ERROR_INVALID_INPUT));
    }

    @Test
    void rejectsCompatibilityOutsideFixedSourceRange() {
        assertThatThrownBy(() -> WmsLogicalMergePlanner.plan(command(
            50,
            Set.of(100L),
            Set.of(200L),
            List.of(assignment(1L, 10L, 100L, TARGET_PACKAGE, 1))
        )))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).contains("兼容作用域必须属于固定来源范围"));
    }

    @Test
    void rejectsInvalidBatchSizeAndSnapshotDigest() {
        assertThatThrownBy(() -> WmsLogicalMergePlanner.plan(command(
            0,
            Set.of(100L),
            Set.of(),
            List.of(assignment(1L, 10L, 100L, TARGET_PACKAGE, 1))
        )))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).contains("合并批大小"));

        WmsLogicalMergePlanner.TargetScope invalidDigest = new WmsLogicalMergePlanner.TargetScope(
            TARGET_SCOPE, TARGET_PACKAGE, "ACTIVE", TARGET_SCOPE, "not-a-digest");
        assertThatThrownBy(() -> WmsLogicalMergePlanner.plan(command(
            invalidDigest, 50, Set.of(100L), Set.of(), List.of(
                assignment(1L, 10L, 100L, TARGET_PACKAGE, 1)))))
            .isInstanceOfSatisfying(ServiceException.class, error ->
                assertThat(error.getMessage()).contains("空间快照"));
    }

    private static WmsLogicalMergePlanner.Command command(
        int batchSize,
        Set<Long> sources,
        Set<Long> compatibleSources,
        List<WmsLogicalMergePlanner.Assignment> assignments
    ) {
        return command(target(), batchSize, sources, compatibleSources, assignments);
    }

    private static WmsLogicalMergePlanner.Command command(
        WmsLogicalMergePlanner.TargetScope target,
        int batchSize,
        Set<Long> sources,
        Set<Long> compatibleSources,
        List<WmsLogicalMergePlanner.Assignment> assignments
    ) {
        return new WmsLogicalMergePlanner.Command(
            target, sources, compatibleSources, batchSize, assignments);
    }

    private static WmsLogicalMergePlanner.TargetScope target() {
        return new WmsLogicalMergePlanner.TargetScope(
            TARGET_SCOPE, TARGET_PACKAGE, "ACTIVE", TARGET_SCOPE, SPACE_DIGEST);
    }

    private static WmsLogicalMergePlanner.Assignment assignment(
        long inventoryId,
        long assignmentId,
        long scopeVersionId,
        long rulePackageVersionId,
        int assignmentRevision
    ) {
        return new WmsLogicalMergePlanner.Assignment(
            inventoryId,
            assignmentId,
            scopeVersionId,
            rulePackageVersionId,
            assignmentRevision
        );
    }
}
