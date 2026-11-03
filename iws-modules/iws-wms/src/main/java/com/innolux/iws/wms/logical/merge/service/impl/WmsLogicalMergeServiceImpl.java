/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 锁内重读目标与物料归属，同事务切换归属、追加历史并记录批次幂等事实；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.logical.merge.WmsLogicalMergePlanner;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeResult;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeResult.MigratedAssignment;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeRetireCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeRetireResult;
import com.innolux.iws.wms.logical.merge.service.WmsLogicalMergeService;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 逻辑归属合并持久化实现。
 *
 * <p>批次先按冻结命令形成稳定输入摘要，再用 {@code wms_command_attempt} 绑定 effectKey。
 * 首次执行时锁定目标作用域和物料当前归属，逐项比较调用方快照后以归属修订号 CAS 切换；
 * 每项归属历史使用由批次 effectKey 派生的唯一效果键，因此批次、归属与历史要么同事务全部
 * 提交，要么全部回滚。重试相同冻结输入直接按历史事实回放，异摘要失败关闭。</p>
 */
@Service
public class WmsLogicalMergeServiceImpl implements WmsLogicalMergeService {

    private static final Pattern EFFECT_KEY_PATTERN = Pattern.compile("^WMS:[0-9a-f]{64}$");

    private static final String ACTION_MERGE = "LOGICAL_MERGE";

    private static final String ACTION_RETIRE = "LOGICAL_MERGE_RETIRE";

    private static final String OBJECT_TYPE = "WMS_LOGICAL_SCOPE_VERSION";

    private static final String FACT_TYPE = "WMS_LOGICAL_ASSIGNMENT_HISTORY";

    private static final String RETIRE_FACT_TYPE = "WMS_LOGICAL_SCOPE_RETIRE";

    private static final String ATTEMPT_APPLIED = "APPLIED";

    private static final String ACTIVE = "ACTIVE";

    private static final String EXECUTION_USER = "USER";

    private static final String EXECUTION_SYSTEM = "SYSTEM";

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final IdentifierGenerator identifierGenerator;

    public WmsLogicalMergeServiceImpl(
        JdbcTemplate jdbcTemplate,
        IdentifierGenerator identifierGenerator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.identifierGenerator = identifierGenerator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsLogicalMergeResult apply(WmsLogicalMergeCommand command) {
        validate(command);
        try {
            return applyLocked(command);
        } catch (ConcurrencyFailureException error) {
            throw conflict(ERROR_CONCURRENCY_RETRY, "合并锁竞争导致事务回滚，可在新事务重试");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsLogicalMergeRetireResult retireSources(WmsLogicalMergeRetireCommand command) {
        validateRetire(command);
        try {
            return retireSourcesLocked(command);
        } catch (ConcurrencyFailureException error) {
            throw conflict(ERROR_CONCURRENCY_RETRY, "来源退役锁竞争导致事务回滚，可在新事务重试");
        }
    }

    private WmsLogicalMergeRetireResult retireSourcesLocked(
        WmsLogicalMergeRetireCommand command
    ) {
        List<Long> sourceIds = command.sourceScopeVersionIds().stream()
            .sorted()
            .toList();
        String inputDigest = retireInputDigest(command, sourceIds);
        AttemptRow existing = selectAttemptForUpdate(retireCommandKey(command.effectKey()));
        if (existing != null) {
            if (!ACTION_RETIRE.equals(existing.action())
                || !inputDigest.equals(existing.inputDigest())
                || !ATTEMPT_APPLIED.equals(existing.status())
                || !RETIRE_FACT_TYPE.equals(existing.factType())) {
                throw conflict(ERROR_EFFECT_CONFLICT, "同一effectKey已绑定不同来源退役输入");
            }
            return replayRetire(command, sourceIds, inputDigest);
        }

        List<SourceRetireRow> sources = lockRetireSources(sourceIds);
        if (sources.size() != sourceIds.size()
            || sources.stream().anyMatch(source -> !ACTIVE.equals(source.versionStatus()))) {
            throw conflict(
                ERROR_SOURCE_VERSION_MISSING,
                "来源作用域版本不存在或已退役"
            );
        }
        long assigned = namedJdbc.queryForObject("""
            select count(*) from wms_material_logical_assignment
             where scope_version_id in (:sourceIds)
            """, Map.of("sourceIds", sourceIds), Long.class);
        if (assigned != 0) {
            throw conflict(
                ERROR_SOURCE_NOT_DRAINED,
                "来源作用域仍有物料归属，不能最终退役"
            );
        }

        insertRetireAttempt(command, sourceIds, inputDigest);
        Map<String, Object> params = new HashMap<>();
        params.put("sourceIds", sourceIds);
        params.put("operatorId", command.operatorId());
        params.put("occurredAt", command.occurredAt());
        params.put("reason", blankToNull(command.reason()));
        namedJdbc.update("""
            update wms_logical_scope_version
               set status = 'RETIRED',
                   update_by = :operatorId,
                   update_time = :occurredAt
             where scope_version_id in (:sourceIds)
            """, params);

        List<Long> scopeIds = sources.stream()
            .map(SourceRetireRow::scopeId)
            .distinct()
            .sorted()
            .toList();
        List<Long> retiredScopeIds = new ArrayList<>();
        for (Long scopeId : scopeIds) {
            long remaining = jdbcTemplate.queryForObject("""
                select count(*) from wms_logical_scope_version
                 where logical_scope_id = ? and status <> 'RETIRED'
                """, Long.class, scopeId);
            if (remaining == 0) {
                int updated = jdbcTemplate.update("""
                    update wms_logical_scope
                       set lifecycle_status = 'RETIRED',
                           revision = revision + 1,
                           update_by = ?, update_time = ?
                     where logical_scope_id = ?
                       and lifecycle_status in ('ACTIVE','RETIRING')
                    """,
                    command.operatorId(), command.occurredAt(), scopeId
                );
                if (updated != 1) {
                    throw conflict(ERROR_EFFECT_CONFLICT, "逻辑归属生命周期退役 CAS 失败");
                }
                retiredScopeIds.add(scopeId);
            }
        }
        return new WmsLogicalMergeRetireResult(
            command.effectKey(), inputDigest, sourceIds, retiredScopeIds, false
        );
    }

    private void validateRetire(WmsLogicalMergeRetireCommand command) {
        if (command == null
            || command.effectKey() == null
            || !EFFECT_KEY_PATTERN.matcher(command.effectKey()).matches()
            || command.sourceScopeVersionIds() == null
            || command.sourceScopeVersionIds().isEmpty()
            || command.occurredAt() == null) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT
                    + ": 来源退役必须提供effectKey、非空来源集合和业务发生时间",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        if (command.sourceScopeVersionIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT + ": 来源版本ID必须为正整数",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        if (command.taskKey() != null && command.taskKey().length() > 96) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT + ": 任务键长度不能超过96",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        if (command.planInstanceId() != null && command.planInstanceId() <= 0) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT + ": 计划实例ID必须为正整数",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
    }

    private WmsLogicalMergeRetireResult replayRetire(
        WmsLogicalMergeRetireCommand command,
        List<Long> sourceIds,
        String inputDigest
    ) {
        List<SourceRetireRow> sources = lockRetireSources(sourceIds);
        if (sources.size() != sourceIds.size()
            || sources.stream().anyMatch(source -> !"RETIRED".equals(source.versionStatus()))) {
            throw conflict(ERROR_EFFECT_CONFLICT, "来源退役回放事实不完整");
        }
        List<Long> scopeIds = sources.stream()
            .map(SourceRetireRow::scopeId)
            .distinct()
            .sorted()
            .toList();
        return new WmsLogicalMergeRetireResult(
            command.effectKey(), inputDigest, sourceIds, scopeIds, true
        );
    }

    private List<SourceRetireRow> lockRetireSources(List<Long> sourceIds) {
        return namedJdbc.query("""
            select version.scope_version_id, version.logical_scope_id,
                   version.status, scope.lifecycle_status
              from wms_logical_scope_version version
              join wms_logical_scope scope
                on scope.logical_scope_id = version.logical_scope_id
             where version.scope_version_id in (:sourceIds)
             order by version.scope_version_id
             for update
            """, Map.of("sourceIds", sourceIds), (rs, rowNum) -> new SourceRetireRow(
            rs.getLong("scope_version_id"),
            rs.getLong("logical_scope_id"),
            rs.getString("status"),
            rs.getString("lifecycle_status")
        ));
    }

    private void insertRetireAttempt(
        WmsLogicalMergeRetireCommand command,
        List<Long> sourceIds,
        String inputDigest
    ) {
        String executionType = command.operatorId() == null ? EXECUTION_SYSTEM : EXECUTION_USER;
        try {
            jdbcTemplate.update("""
                insert into wms_command_attempt (
                    attempt_id, command_key, action, object_type, object_id,
                    input_digest, input_summary, status, fact_type, fact_id,
                    actor_user_id, actor_name, dept_id, dept_name,
                    initiator_name, execution_subject, execution_subject_type,
                    occurred_at, recorded_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                identifierGenerator.nextId(null),
                retireCommandKey(command.effectKey()),
                ACTION_RETIRE,
                OBJECT_TYPE,
                sourceIds.getFirst(),
                inputDigest,
                "LOGICAL_MERGE_RETIRE sources=" + sourceIds
                    + " digest=" + inputDigest,
                ATTEMPT_APPLIED,
                RETIRE_FACT_TYPE,
                inputDigest,
                command.operatorId(),
                limit(command.operatorName(), 64),
                command.operatorDepartmentId(),
                limit(command.operatorDepartmentName(), 128),
                limit(command.operatorName(), 64),
                limit(command.operatorName(), 64),
                executionType,
                command.occurredAt(),
                LocalDateTime.now()
            );
        } catch (DuplicateKeyException error) {
            throw conflict(ERROR_EFFECT_CONFLICT, "同一effectKey已并发完成来源退役");
        }
    }

    private static String retireCommandKey(String effectKey) {
        return WmsRuleDigest.sha256("iws.wms.logical-merge-retire-effect/1\n" + effectKey);
    }

    private static String retireInputDigest(
        WmsLogicalMergeRetireCommand command,
        List<Long> sourceIds
    ) {
        StringBuilder canonical = new StringBuilder("iws.wms.logical-merge-retire/1\n");
        canonical.append("effectKey=").append(command.effectKey()).append('\n');
        sourceIds.forEach(id -> canonical.append("source=").append(id).append('\n'));
        canonical.append("taskKey=").append(command.taskKey()).append('\n');
        canonical.append("planInstanceId=").append(command.planInstanceId()).append('\n');
        canonical.append("occurredAt=").append(command.occurredAt()).append('\n');
        canonical.append("reason=").append(command.reason()).append('\n');
        canonical.append("operatorId=").append(command.operatorId()).append('\n');
        return WmsRuleDigest.sha256(canonical.toString());
    }

    private WmsLogicalMergeResult applyLocked(WmsLogicalMergeCommand command) {
        WmsLogicalMergePlanner.Plan commandPlan = WmsLogicalMergePlanner.plan(command.planCommand());
        String commandKey = commandKey(command.effectKey());
        AttemptRow existing = selectAttemptForUpdate(commandKey);
        if (existing != null) {
            return replay(command, commandPlan, existing);
        }

        TargetRow target = lockTarget(command.target().scopeVersionId());
        assertTarget(command.target(), target);
        Map<Long, AssignmentRow> current = lockAssignments(command.expectedAssignments());
        assertAssignments(command.expectedAssignments(), current);

        List<WmsLogicalMergePlanner.Assignment> lockedAssignments = current.values().stream()
            .map(AssignmentRow::toPlannerAssignment)
            .toList();
        WmsLogicalMergePlanner.Plan plan = WmsLogicalMergePlanner.plan(
            new WmsLogicalMergePlanner.Command(
                new WmsLogicalMergePlanner.TargetScope(
                    target.scopeVersionId(),
                    target.rulePackageVersionId(),
                    target.lifecycleStatus(),
                    target.currentVersionId(),
                    target.spaceSnapshotDigest()
                ),
                command.sourceScopeVersionIds(),
                command.compatibleSourceScopeVersionIds(),
                command.batchSize(),
                lockedAssignments
            )
        );
        if (!Objects.equals(commandPlan.digest(), plan.digest())) {
            throw conflict(ERROR_ASSIGNMENT_CONFLICT, "锁后归属事实与冻结输入摘要不一致");
        }

        retireSources(command);
        insertAttempt(command, commandKey, plan.digest());
        List<MigratedAssignment> migrated = migrate(command, plan);
        Counts counts = counts(plan);
        return new WmsLogicalMergeResult(
            command.effectKey(),
            plan.digest(),
            command.target().scopeVersionId(),
            plan.batches(),
            migrated,
            counts.migrated(),
            counts.alreadyTarget(),
            counts.conflictRetained(),
            false
        );
    }

    private void validate(WmsLogicalMergeCommand command) {
        if (command == null
            || command.effectKey() == null
            || !EFFECT_KEY_PATTERN.matcher(command.effectKey()).matches()) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT + ": effectKey必须为WMS:加小写64位SHA-256",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        if (command.occurredAt() == null) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT + ": 业务发生时间不能为空",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        if (command.planInstanceId() != null && command.planInstanceId() <= 0) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT + ": 计划实例ID必须为正整数",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        if (command.taskKey() != null && command.taskKey().length() > 96) {
            throw new ServiceException(
                WmsLogicalMergePlanner.ERROR_INVALID_INPUT + ": 任务键长度不能超过96",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
        if (command.expectedAssignments() != null
            && command.expectedAssignments().size() > command.batchSize()) {
            throw new ServiceException(
                ERROR_BATCH_EXCEEDS_LIMIT
                    + ": 单次合并动作只能处理一个持久批次，预期物料数不能超过batchSize",
                HttpStatus.HTTP_BAD_REQUEST
            );
        }
    }

    private void retireSources(WmsLogicalMergeCommand command) {
        List<Long> sourceIds = command.sourceScopeVersionIds().stream()
            .sorted()
            .toList();
        List<SourceRow> sources = namedJdbc.query("""
            select version.scope_version_id, version.status,
                   scope.lifecycle_status
              from wms_logical_scope_version version
              join wms_logical_scope scope
                on scope.logical_scope_id = version.logical_scope_id
             where version.scope_version_id in (:sourceIds)
             order by version.scope_version_id
             for update
            """, Map.of("sourceIds", sourceIds), (rs, rowNum) -> new SourceRow(
            rs.getLong("scope_version_id"),
            rs.getString("status"),
            rs.getString("lifecycle_status")
        ));
        if (sources.size() != sourceIds.size()
            || sources.stream().anyMatch(source ->
                !ACTIVE.equals(source.versionStatus())
                || !Set.of("ACTIVE", "RETIRING").contains(source.lifecycleStatus()))) {
            throw conflict(
                ERROR_SOURCE_VERSION_MISSING,
                "来源作用域版本不存在、已退役或父链不完整"
            );
        }
        Map<String, Object> params = new HashMap<>();
        params.put("sourceIds", sourceIds);
        params.put("operatorId", command.operatorId());
        params.put("occurredAt", command.occurredAt());
        namedJdbc.update("""
            update wms_logical_scope scope
              join wms_logical_scope_version version
                on version.logical_scope_id = scope.logical_scope_id
               set scope.lifecycle_status = 'RETIRING',
                   scope.revision = scope.revision + 1,
                   scope.update_by = :operatorId,
                   scope.update_time = :occurredAt
             where version.scope_version_id in (:sourceIds)
               and scope.lifecycle_status in ('ACTIVE','RETIRING')
            """, params);
    }

    private TargetRow lockTarget(long targetScopeVersionId) {
        List<TargetRow> rows = jdbcTemplate.query("""
            select version.scope_version_id,
                   version.rule_package_version_id,
                   version.space_snapshot_digest,
                   version.status as version_status,
                   scope.lifecycle_status,
                   scope.current_version_id,
                   package.status as package_status
              from wms_logical_scope_version version
              join wms_logical_scope scope
                on scope.logical_scope_id = version.logical_scope_id
              join wms_rule_package_version package
                on package.package_version_id = version.rule_package_version_id
             where version.scope_version_id = ?
             for share
            """, (rs, rowNum) -> targetRow(rs), targetScopeVersionId);
        if (rows.size() != 1) {
            throw conflict(
                WmsLogicalMergePlanner.ERROR_TARGET_NOT_EFFECTIVE,
                "目标作用域版本不存在或父链不完整"
            );
        }
        TargetRow target = rows.get(0);
        if (!ACTIVE.equals(target.lifecycleStatus())
            || !ACTIVE.equals(target.versionStatus())
            || !ACTIVE.equals(target.packageStatus())) {
            throw conflict(
                WmsLogicalMergePlanner.ERROR_TARGET_NOT_EFFECTIVE,
                "目标作用域、版本或规则包不是ACTIVE"
            );
        }
        return target;
    }

    private void assertTarget(
        WmsLogicalMergePlanner.TargetScope expected,
        TargetRow current
    ) {
        if (current.scopeVersionId() != expected.scopeVersionId()
            || current.rulePackageVersionId() != expected.rulePackageVersionId()
            || !Objects.equals(current.lifecycleStatus(), expected.lifecycleStatus())
            || !Objects.equals(current.currentVersionId(), expected.currentVersionId())
            || !Objects.equals(current.spaceSnapshotDigest(), expected.spaceSnapshotDigest())) {
            throw conflict(ERROR_TARGET_CHANGED, "目标生效事实在锁前变化");
        }
    }

    private Map<Long, AssignmentRow> lockAssignments(
        List<WmsLogicalMergePlanner.Assignment> expected
    ) {
        List<Long> inventoryIds = expected.stream()
            .map(WmsLogicalMergePlanner.Assignment::inventoryId)
            .sorted()
            .toList();
        List<AssignmentIdentity> identities = namedJdbc.query("""
            select logical_assignment_id,
                   inventory_id,
                   scope_version_id,
                   assignment_revision
              from wms_material_logical_assignment
             where inventory_id in (:inventoryIds)
             order by inventory_id
             for update
            """, Map.of("inventoryIds", inventoryIds),
            (rs, rowNum) -> new AssignmentIdentity(
                rs.getLong("logical_assignment_id"),
                rs.getLong("inventory_id"),
                rs.getLong("scope_version_id"),
                rs.getInt("assignment_revision")
            ));

        Set<Long> scopeVersionIds = identities.stream()
            .map(AssignmentIdentity::scopeVersionId)
            .collect(java.util.stream.Collectors.toSet());
        Map<Long, Long> packageByScopeVersion = packageByScopeVersion(scopeVersionIds);
        Map<Long, AssignmentRow> rows = new LinkedHashMap<>();
        for (AssignmentIdentity identity : identities) {
            Long rulePackageVersionId = packageByScopeVersion.get(identity.scopeVersionId());
            if (rulePackageVersionId == null) {
                throw conflict(
                    ERROR_SOURCE_VERSION_MISSING,
                    "物料当前作用域版本不存在: " + identity.scopeVersionId()
                );
            }
            rows.put(identity.inventoryId(), new AssignmentRow(
                identity.inventoryId(),
                identity.assignmentId(),
                identity.scopeVersionId(),
                rulePackageVersionId,
                identity.assignmentRevision()
            ));
        }
        return rows;
    }

    private Map<Long, Long> packageByScopeVersion(Set<Long> scopeVersionIds) {
        if (scopeVersionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        namedJdbc.query("""
            select scope_version_id, rule_package_version_id
              from wms_logical_scope_version
             where scope_version_id in (:scopeVersionIds)
            """, Map.of("scopeVersionIds", scopeVersionIds), rs -> {
                result.put(
                    rs.getLong("scope_version_id"),
                    rs.getLong("rule_package_version_id")
                );
            });
        return result;
    }

    private void assertAssignments(
        List<WmsLogicalMergePlanner.Assignment> expected,
        Map<Long, AssignmentRow> current
    ) {
        if (expected.size() != current.size()) {
            throw conflict(ERROR_ASSIGNMENT_CONFLICT, "部分物料当前归属不存在");
        }
        for (WmsLogicalMergePlanner.Assignment assignment : expected) {
            AssignmentRow row = current.get(assignment.inventoryId());
            if (row == null
                || row.assignmentId() != assignment.assignmentId()
                || row.scopeVersionId() != assignment.scopeVersionId()
                || row.rulePackageVersionId() != assignment.rulePackageVersionId()
                || row.assignmentRevision() != assignment.assignmentRevision()) {
                throw conflict(
                    ERROR_ASSIGNMENT_CONFLICT,
                    "物料当前归属与冻结快照不一致: " + assignment.inventoryId()
                );
            }
        }
    }

    private void insertAttempt(WmsLogicalMergeCommand command, String commandKey, String inputDigest) {
        LocalDateTime recordedAt = LocalDateTime.now();
        String executionType = command.operatorId() == null ? EXECUTION_SYSTEM : EXECUTION_USER;
        try {
            jdbcTemplate.update("""
                insert into wms_command_attempt (
                    attempt_id, command_key, action, object_type, object_id,
                    input_digest, input_summary, status, fact_type, fact_id,
                    actor_user_id, actor_name, dept_id, dept_name,
                    initiator_name, execution_subject, execution_subject_type,
                    occurred_at, recorded_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                identifierGenerator.nextId(null),
                commandKey,
                ACTION_MERGE,
                OBJECT_TYPE,
                command.target().scopeVersionId(),
                inputDigest,
                inputSummary(command, inputDigest),
                ATTEMPT_APPLIED,
                FACT_TYPE,
                inputDigest,
                command.operatorId(),
                limit(command.operatorName(), 64),
                command.operatorDepartmentId(),
                limit(command.operatorDepartmentName(), 128),
                limit(command.operatorName(), 64),
                limit(command.operatorName(), 64),
                executionType,
                command.occurredAt(),
                recordedAt
            );
        } catch (DuplicateKeyException error) {
            throw conflict(ERROR_EFFECT_CONFLICT, "同一effectKey已并发生效");
        }
    }

    private String inputSummary(WmsLogicalMergeCommand command, String inputDigest) {
        return "LOGICAL_MERGE target=" + command.target().scopeVersionId()
            + " sources=" + command.sourceScopeVersionIds().size()
            + " assignments=" + command.expectedAssignments().size()
            + " digest=" + inputDigest;
    }

    private List<MigratedAssignment> migrate(
        WmsLogicalMergeCommand command,
        WmsLogicalMergePlanner.Plan plan
    ) {
        List<MigratedAssignment> migrated = new ArrayList<>();
        for (WmsLogicalMergePlanner.Decision decision : plan.decisions()) {
            if (decision.action() != WmsLogicalMergePlanner.Action.MIGRATE) {
                continue;
            }
            int currentRevision = decision.assignmentRevision() + 1;
            int updated = jdbcTemplate.update("""
                update wms_material_logical_assignment
                   set scope_version_id = ?,
                       assignment_revision = assignment_revision + 1,
                       revision = revision + 1
                 where logical_assignment_id = ?
                   and inventory_id = ?
                   and scope_version_id = ?
                   and assignment_revision = ?
                """,
                command.target().scopeVersionId(),
                decision.assignmentId(),
                decision.inventoryId(),
                decision.scopeVersionId(),
                decision.assignmentRevision()
            );
            if (updated != 1) {
                throw conflict(
                    ERROR_ASSIGNMENT_CONFLICT,
                    "物料归属CAS失败: " + decision.inventoryId()
                );
            }
            long historyId = insertHistory(command, decision);
            migrated.add(new MigratedAssignment(
                historyId,
                decision.inventoryId(),
                decision.assignmentId(),
                decision.scopeVersionId(),
                command.target().scopeVersionId(),
                decision.assignmentRevision(),
                currentRevision
            ));
        }
        return List.copyOf(migrated);
    }

    private long insertHistory(
        WmsLogicalMergeCommand command,
        WmsLogicalMergePlanner.Decision decision
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                insert into wms_logical_assignment_history (
                    inventory_id, from_scope_version_id, to_scope_version_id,
                    task_key, plan_instance_id, effect_key,
                    operator_id, operator_name, operator_department_id,
                    operator_department_name, occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[]{"logical_assignment_history_id"});
            statement.setLong(1, decision.inventoryId());
            statement.setLong(2, decision.scopeVersionId());
            statement.setLong(3, command.target().scopeVersionId());
            statement.setString(4, blankToNull(command.taskKey()));
            if (command.planInstanceId() == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, command.planInstanceId());
            }
            statement.setString(6, historyEffectKey(command.effectKey(), decision));
            if (command.operatorId() == null) {
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setLong(7, command.operatorId());
            }
            statement.setString(8, limit(command.operatorName(), 128));
            if (command.operatorDepartmentId() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, command.operatorDepartmentId());
            }
            statement.setString(10, limit(command.operatorDepartmentName(), 128));
            statement.setObject(11, command.occurredAt());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ServiceException(ERROR_HISTORY_INCONSISTENT + ": 归属历史未返回主键");
        }
        return key.longValue();
    }

    private WmsLogicalMergeResult replay(
        WmsLogicalMergeCommand command,
        WmsLogicalMergePlanner.Plan plan,
        AttemptRow existing
    ) {
        if (!ACTION_MERGE.equals(existing.action())
            || !plan.digest().equals(existing.inputDigest())) {
            throw conflict(ERROR_EFFECT_CONFLICT, "同一effectKey已绑定不同合并输入");
        }
        if (!ATTEMPT_APPLIED.equals(existing.status())) {
            throw conflict(ERROR_EFFECT_CONFLICT, "同批次尚未成功完成");
        }
        if (!FACT_TYPE.equals(existing.factType())) {
            throw conflict(ERROR_HISTORY_INCONSISTENT, "批次成功事实类型不一致");
        }

        List<WmsLogicalMergePlanner.Decision> migrations = plan.decisions().stream()
            .filter(decision -> decision.action() == WmsLogicalMergePlanner.Action.MIGRATE)
            .toList();
        Map<String, HistoryRow> histories = histories(
            migrations.stream()
                .map(decision -> historyEffectKey(command.effectKey(), decision))
                .toList()
        );
        List<MigratedAssignment> migrated = new ArrayList<>();
        for (WmsLogicalMergePlanner.Decision decision : migrations) {
            String key = historyEffectKey(command.effectKey(), decision);
            HistoryRow history = histories.get(key);
            if (history == null
                || history.inventoryId() != decision.inventoryId()
                || history.fromScopeVersionId() != decision.scopeVersionId()
                || history.toScopeVersionId() != command.target().scopeVersionId()) {
                throw conflict(
                    ERROR_HISTORY_INCONSISTENT,
                    "批次历史事实不完整: " + decision.inventoryId()
                );
            }
            migrated.add(new MigratedAssignment(
                history.historyId(),
                decision.inventoryId(),
                decision.assignmentId(),
                decision.scopeVersionId(),
                command.target().scopeVersionId(),
                decision.assignmentRevision(),
                decision.assignmentRevision() + 1
            ));
        }
        if (histories.size() != migrations.size()) {
            throw conflict(ERROR_HISTORY_INCONSISTENT, "批次历史存在额外事实");
        }

        Counts counts = counts(plan);
        return new WmsLogicalMergeResult(
            command.effectKey(),
            plan.digest(),
            command.target().scopeVersionId(),
            plan.batches(),
            migrated,
            counts.migrated(),
            counts.alreadyTarget(),
            counts.conflictRetained(),
            true
        );
    }

    private Map<String, HistoryRow> histories(List<String> effectKeys) {
        if (effectKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, HistoryRow> result = new HashMap<>();
        namedJdbc.query("""
            select logical_assignment_history_id, inventory_id,
                   from_scope_version_id, to_scope_version_id, effect_key
              from wms_logical_assignment_history
             where effect_key in (:effectKeys)
            """, Map.of("effectKeys", effectKeys), rs -> {
                result.put(rs.getString("effect_key"), new HistoryRow(
                    rs.getLong("logical_assignment_history_id"),
                    rs.getLong("inventory_id"),
                    rs.getLong("from_scope_version_id"),
                    rs.getLong("to_scope_version_id")
                ));
            });
        return result;
    }

    private AttemptRow selectAttemptForUpdate(String commandKey) {
        List<AttemptRow> rows = jdbcTemplate.query("""
            select action, input_digest, status, fact_type
              from wms_command_attempt
             where command_key = ?
             for update
            """, (rs, rowNum) -> new AttemptRow(
                rs.getString("action"),
                rs.getString("input_digest"),
                rs.getString("status"),
                rs.getString("fact_type")
            ), commandKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static TargetRow targetRow(ResultSet resultSet) throws SQLException {
        return new TargetRow(
            resultSet.getLong("scope_version_id"),
            resultSet.getLong("rule_package_version_id"),
            resultSet.getString("version_status"),
            resultSet.getString("lifecycle_status"),
            resultSet.getLong("current_version_id"),
            resultSet.getString("space_snapshot_digest"),
            resultSet.getString("package_status")
        );
    }

    private static Counts counts(WmsLogicalMergePlanner.Plan plan) {
        int migrated = 0;
        int alreadyTarget = 0;
        int conflicts = 0;
        for (WmsLogicalMergePlanner.Decision decision : plan.decisions()) {
            switch (decision.action()) {
                case MIGRATE -> migrated++;
                case ALREADY_TARGET -> alreadyTarget++;
                case CONFLICT_RETAINED -> conflicts++;
            }
        }
        return new Counts(migrated, alreadyTarget, conflicts);
    }

    private static String commandKey(String effectKey) {
        return WmsRuleDigest.sha256("iws.wms.logical-merge-effect/1\n" + effectKey);
    }

    private static String historyEffectKey(
        String effectKey,
        WmsLogicalMergePlanner.Decision decision
    ) {
        String canonical = "iws.wms.logical-assignment-history/1\n"
            + effectKey + "\n"
            + decision.inventoryId() + "\n"
            + decision.assignmentId() + "\n"
            + decision.scopeVersionId() + "\n"
            + decision.assignmentRevision();
        return "WMS:" + WmsRuleDigest.sha256(canonical);
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, HttpStatus.HTTP_CONFLICT);
    }

    private record TargetRow(
        long scopeVersionId,
        long rulePackageVersionId,
        String versionStatus,
        String lifecycleStatus,
        Long currentVersionId,
        String spaceSnapshotDigest,
        String packageStatus
    ) {
    }

    private record SourceRow(
        long scopeVersionId,
        String versionStatus,
        String lifecycleStatus
    ) {
    }

    private record SourceRetireRow(
        long scopeVersionId,
        long scopeId,
        String versionStatus,
        String lifecycleStatus
    ) {
    }

    private record AssignmentIdentity(
        long assignmentId,
        long inventoryId,
        long scopeVersionId,
        int assignmentRevision
    ) {
    }

    private record AssignmentRow(
        long inventoryId,
        long assignmentId,
        long scopeVersionId,
        long rulePackageVersionId,
        int assignmentRevision
    ) {

        WmsLogicalMergePlanner.Assignment toPlannerAssignment() {
            return new WmsLogicalMergePlanner.Assignment(
                inventoryId,
                assignmentId,
                scopeVersionId,
                rulePackageVersionId,
                assignmentRevision
            );
        }
    }

    private record AttemptRow(
        String action,
        String inputDigest,
        String status,
        String factType
    ) {
    }

    private record HistoryRow(
        long historyId,
        long inventoryId,
        long fromScopeVersionId,
        long toScopeVersionId
    ) {
    }

    private record Counts(int migrated, int alreadyTarget, int conflictRetained) {
    }
}
