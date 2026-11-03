/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 按固定来源范围、目标生效版本和规则兼容事实生成稳定合批计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 逻辑归属合并计划内核。
 *
 * <p>只根据冻结的目标作用域、来源作用域集合、兼容作用域集合和物料当前归属
 * 生成确定性分批计划；不移动物料、不复制容量、不写历史。规则包版本相同或来源
 * 作用域版本被显式列为兼容时才允许迁移。其余物料保留原归属并形成可审计冲突项。</p>
 */
public final class WmsLogicalMergePlanner {

    public static final String ERROR_INVALID_INPUT = "LOGICAL_MERGE_INPUT_INVALID";

    public static final String ERROR_TARGET_NOT_EFFECTIVE = "LOGICAL_MERGE_TARGET_NOT_EFFECTIVE";

    public static final int MAX_BATCH_SIZE = 500;

    private static final Pattern DIGEST_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private WmsLogicalMergePlanner() {
    }

    /**
     * 生成固定范围的逻辑归属合并计划。
     *
     * @param command 目标作用域、固定来源范围、兼容事实和当前归属快照
     * @return 按物料 ID 稳定排序的分批计划
     * @throws ServiceException 输入为空、范围非法、目标未生效或物料重复时
     */
    public static Plan plan(Command command) {
        validate(command);
        TargetScope target = command.target();

        List<Assignment> assignments = new ArrayList<>(command.assignments());
        assignments.sort(Comparator.comparingLong(Assignment::inventoryId));
        Set<Long> inventoryIds = new HashSet<>();
        List<Decision> decisions = new ArrayList<>(assignments.size());
        for (Assignment assignment : assignments) {
            validateAssignment(assignment);
            if (!inventoryIds.add(assignment.inventoryId())) {
                throw invalid("同一合并范围存在重复物料: " + assignment.inventoryId());
            }
            decisions.add(decide(target, command, assignment));
        }

        List<Batch> batches = new ArrayList<>();
        for (int offset = 0; offset < decisions.size(); offset += command.batchSize()) {
            int end = Math.min(offset + command.batchSize(), decisions.size());
            batches.add(new Batch(
                batches.size() + 1,
                List.copyOf(decisions.subList(offset, end))
            ));
        }
        return new Plan(planDigest(command), List.copyOf(decisions), List.copyOf(batches));
    }

    private static void validate(Command command) {
        if (command == null || command.target() == null
            || command.sourceScopeVersionIds() == null
            || command.sourceScopeVersionIds().isEmpty()
            || command.compatibleSourceScopeVersionIds() == null
            || command.assignments() == null
            || command.assignments().isEmpty()) {
            throw invalid("目标、来源范围、兼容范围和物料快照不能为空");
        }
        if (command.batchSize() < 1 || command.batchSize() > MAX_BATCH_SIZE) {
            throw invalid("合并批大小必须为1到" + MAX_BATCH_SIZE);
        }
        validatePositiveIds(command.sourceScopeVersionIds(), "来源作用域版本");
        validatePositiveIds(command.compatibleSourceScopeVersionIds(), "兼容作用域版本");
        if (!command.sourceScopeVersionIds()
            .containsAll(command.compatibleSourceScopeVersionIds())) {
            throw invalid("兼容作用域必须属于固定来源范围");
        }

        TargetScope target = command.target();
        if (target.scopeVersionId() <= 0 || target.rulePackageVersionId() <= 0) {
            throw invalid("目标作用域版本和规则包版本必须为正整数");
        }
        if (target.currentVersionId() == null
            || target.currentVersionId() != target.scopeVersionId()
            || !"ACTIVE".equals(target.lifecycleStatus())) {
            throw new ServiceException(ERROR_TARGET_NOT_EFFECTIVE + ": 目标作用域不是当前生效版本");
        }
        if (target.spaceSnapshotDigest() == null
            || !DIGEST_PATTERN.matcher(target.spaceSnapshotDigest()).matches()) {
            throw invalid("目标空间快照必须为小写64位SHA-256");
        }
    }

    private static void validatePositiveIds(Set<Long> values, String label) {
        if (values.stream().anyMatch(value -> value == null || value <= 0)) {
            throw invalid(label + "必须为正整数");
        }
    }

    private static void validateAssignment(Assignment assignment) {
        if (assignment == null
            || assignment.inventoryId() <= 0
            || assignment.assignmentId() <= 0
            || assignment.scopeVersionId() <= 0
            || assignment.rulePackageVersionId() <= 0
            || assignment.assignmentRevision() < 1) {
            throw invalid("物料当前逻辑归属身份或修订号非法");
        }
    }

    private static Decision decide(TargetScope target, Command command, Assignment assignment) {
        if (assignment.scopeVersionId() == target.scopeVersionId()) {
            return decision(assignment, Action.ALREADY_TARGET, Reason.TARGET_SCOPE);
        }
        if (!command.sourceScopeVersionIds().contains(assignment.scopeVersionId())) {
            return decision(assignment, Action.CONFLICT_RETAINED, Reason.SOURCE_OUT_OF_SCOPE);
        }
        if (assignment.rulePackageVersionId() != target.rulePackageVersionId()
            && !command.compatibleSourceScopeVersionIds()
            .contains(assignment.scopeVersionId())) {
            return decision(assignment, Action.CONFLICT_RETAINED, Reason.RULE_INCOMPATIBLE);
        }
        return decision(assignment, Action.MIGRATE, Reason.COMPATIBLE);
    }

    private static Decision decision(Assignment assignment, Action action, Reason reason) {
        return new Decision(
            assignment.inventoryId(),
            assignment.assignmentId(),
            assignment.scopeVersionId(),
            assignment.assignmentRevision(),
            action,
            reason
        );
    }

    private static String planDigest(Command command) {
        StringBuilder canonical = new StringBuilder(512);
        field(canonical, "schema", "iws.wms.logical-merge-plan/1");
        field(canonical, "targetScopeVersionId", command.target().scopeVersionId());
        field(canonical, "targetRulePackageVersionId", command.target().rulePackageVersionId());
        field(canonical, "targetSpaceSnapshotDigest", command.target().spaceSnapshotDigest());
        field(canonical, "batchSize", command.batchSize());
        command.sourceScopeVersionIds().stream().sorted()
            .forEach(value -> field(canonical, "sourceScopeVersionId", value));
        command.compatibleSourceScopeVersionIds().stream().sorted()
            .forEach(value -> field(canonical, "compatibleSourceScopeVersionId", value));
        command.assignments().stream()
            .sorted(Comparator.comparingLong(Assignment::inventoryId))
            .forEach(item -> {
                field(canonical, "inventoryId", item.inventoryId());
                field(canonical, "assignmentId", item.assignmentId());
                field(canonical, "scopeVersionId", item.scopeVersionId());
                field(canonical, "rulePackageVersionId", item.rulePackageVersionId());
                field(canonical, "assignmentRevision", item.assignmentRevision());
            });
        return WmsRuleDigest.sha256(canonical.toString());
    }

    private static void field(StringBuilder target, String name, Object value) {
        String text = String.valueOf(value);
        target.append(name).append('=').append(text.length()).append(':').append(text).append('\n');
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(ERROR_INVALID_INPUT + ": " + message);
    }

    /**
     * 合并计划输入。
     *
     * @param target 目标生效作用域版本
     * @param sourceScopeVersionIds 固定来源作用域版本集合
     * @param compatibleSourceScopeVersionIds 虽规则包版本不同但已确认兼容的来源版本
     * @param batchSize 每批最大物料数
     * @param assignments 物料当前归属快照
     */
    public record Command(
        TargetScope target,
        Set<Long> sourceScopeVersionIds,
        Set<Long> compatibleSourceScopeVersionIds,
        int batchSize,
        List<Assignment> assignments
    ) {
    }

    /**
     * 目标作用域生效事实。
     *
     * @param scopeVersionId 目标作用域版本 ID
     * @param rulePackageVersionId 目标规则包版本 ID
     * @param lifecycleStatus 目标作用域生命周期
     * @param currentVersionId 当前生效指针
     * @param spaceSnapshotDigest 目标空间快照摘要
     */
    public record TargetScope(
        long scopeVersionId,
        long rulePackageVersionId,
        String lifecycleStatus,
        Long currentVersionId,
        String spaceSnapshotDigest
    ) {
    }

    /**
     * 物料当前逻辑归属快照。
     *
     * @param inventoryId 稳定库存 ID
     * @param assignmentId 当前逻辑归属 ID
     * @param scopeVersionId 当前作用域版本 ID
     * @param rulePackageVersionId 当前规则包版本 ID
     * @param assignmentRevision 当前归属修订号
     */
    public record Assignment(
        long inventoryId,
        long assignmentId,
        long scopeVersionId,
        long rulePackageVersionId,
        int assignmentRevision
    ) {
    }

    /**
     * 单物料合并决定。
     *
     * @param inventoryId 稳定库存 ID
     * @param assignmentId 当前逻辑归属 ID
     * @param scopeVersionId 当前作用域版本 ID
     * @param assignmentRevision 当前归属修订号
     * @param action 合并动作
     * @param reason 合并原因
     */
    public record Decision(
        long inventoryId,
        long assignmentId,
        long scopeVersionId,
        int assignmentRevision,
        Action action,
        Reason reason
    ) {
    }

    /**
     * 稳定批。
     *
     * @param batchNo 从 1 开始的批号
     * @param decisions 本批决定
     */
    public record Batch(int batchNo, List<Decision> decisions) {
    }

    /**
     * 合并计划结果。
     *
     * @param digest 计划规范化摘要
     * @param decisions 全部决定，按物料 ID 排序
     * @param batches 固定大小分批
     */
    public record Plan(String digest, List<Decision> decisions, List<Batch> batches) {
    }

    /** 单物料合并动作。 */
    public enum Action {
        MIGRATE,
        ALREADY_TARGET,
        CONFLICT_RETAINED
    }

    /** 单物料合并原因。 */
    public enum Reason {
        COMPATIBLE,
        TARGET_SCOPE,
        SOURCE_OUT_OF_SCOPE,
        RULE_INCOMPATIBLE
    }
}
