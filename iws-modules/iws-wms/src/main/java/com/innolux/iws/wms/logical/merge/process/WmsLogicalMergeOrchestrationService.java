/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 推进固定批次、完成屏障、父收敛和来源退役事务；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.wms.logical.merge.WmsLogicalMergePlanner;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeResult;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeRetireCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeStartCommand;
import com.innolux.iws.wms.logical.merge.service.WmsLogicalMergeService;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCompleteCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskTransitionCommand;
import com.innolux.iws.wms.task.service.WmsBusinessTaskBarrierService;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 逻辑合并父、子计划动作事务服务。 */
@Service
public class WmsLogicalMergeOrchestrationService {

    public static final String ERROR_CONFLICTS_REMAIN =
        "LOGICAL_MERGE_CONFLICTS_REMAIN";

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final JsonMapper jsonMapper;

    private final WmsLogicalMergeService mergeService;

    private final WmsBusinessTaskService taskService;

    private final WmsBusinessTaskBarrierService barrierService;

    public WmsLogicalMergeOrchestrationService(
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        WmsLogicalMergeService mergeService,
        WmsBusinessTaskService taskService,
        WmsBusinessTaskBarrierService barrierService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.jsonMapper = jsonMapper;
        this.mergeService = mergeService;
        this.taskService = taskService;
        this.barrierService = barrierService;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public ParentSnapshot captureParent(long mergeOrderId) {
        ParentRow order = lockOrder(mergeOrderId);
        List<BatchRow> batches = batches(mergeOrderId);
        if (batches.size() != order.expectedBatchCount()) {
            throw conflict("逻辑合并固定批次集合不完整");
        }
        WmsBusinessTaskBarrierSnapshot barrier = barrierService.get(
            WmsLogicalMergePlanContract.BUSINESS_TYPE,
            mergeOrderId,
            WmsLogicalMergePlanContract.BARRIER_KEY
        );
        List<String> actual = batches.stream().map(BatchRow::taskKey).sorted().toList();
        List<String> expected = barrier.expectedTaskKeys().stream().sorted().toList();
        if (!actual.equals(expected)) {
            throw conflict("逻辑合并固定批次与完成屏障预期集合不一致");
        }
        startTask(order.parentBusinessTaskId());
        return new ParentSnapshot(
            mergeOrderId, order.targetScopeVersionId(),
            order.expectedBatchCount(), barrier.barrierId(),
            barrier.status(), barrier.revision()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public ParentSnapshot closeParent(long mergeOrderId) {
        ParentRow order = lockOrder(mergeOrderId);
        if ("COMPLETED".equals(order.status())) {
            return parentSnapshot(order);
        }
        if (!"RUNNING".equals(order.status()) && !"VERIFYING".equals(order.status())) {
            throw conflict("逻辑合并父单状态不允许收敛: " + order.status());
        }
        WmsBusinessTaskBarrierSnapshot barrier = barrierService.get(
            WmsLogicalMergePlanContract.BUSINESS_TYPE,
            mergeOrderId,
            WmsLogicalMergePlanContract.BARRIER_KEY
        );
        WmsBusinessTaskBarrierSnapshot completed = barrierService.complete(
            new WmsBusinessTaskBarrierCompleteCommand(
                WmsLogicalMergePlanContract.BUSINESS_TYPE,
                mergeOrderId,
                WmsLogicalMergePlanContract.BARRIER_KEY,
                barrier.revision(),
                LoginHelper.getUserId()
            )
        );
        int conflicts = totalConflictCount(mergeOrderId);
        jdbcTemplate.update("""
            update wms_logical_merge_order
               set conflict_count = ?, status = 'VERIFYING', revision = revision + 1,
                   update_by = ?, update_time = current_timestamp(3)
             where merge_order_id = ?
            """, conflicts, LoginHelper.getUserId(), mergeOrderId);
        if (conflicts > 0) {
            throw new ServiceException(
                ERROR_CONFLICTS_REMAIN + ": 仍有 " + conflicts + " 条物料保留旧归属"
            );
        }
        WmsLogicalMergeStartCommand command = jsonMapper.readValue(
            order.inputJson(), WmsLogicalMergeStartCommand.class);
        WmsLogicalMergeRetireCommand retire = new WmsLogicalMergeRetireCommand(
            "WMS:" + WmsRuleDigest.sha256(
                "logical-merge-close/1\n" + order.commandKey()),
            command.sourceScopeVersionIds(),
            order.commandKey(),
            order.parentPlanInstanceId(),
            LocalDateTime.now(),
            order.reason(),
            LoginHelper.getUserId(),
            LoginHelper.getUsername(),
            LoginHelper.getDeptId(),
            LoginHelper.getDeptName()
        );
        mergeService.retireSources(retire);
        completeTask(order.parentBusinessTaskId());
        int rows = jdbcTemplate.update("""
            update wms_logical_merge_order
               set status = 'COMPLETED', revision = revision + 1,
                   update_by = ?, update_time = current_timestamp(3)
             where merge_order_id = ? and status = 'VERIFYING'
            """, LoginHelper.getUserId(), mergeOrderId);
        if (rows != 1) {
            throw conflict("逻辑合并父单收敛发生并发竞争");
        }
        return new ParentSnapshot(
            mergeOrderId,
            order.targetScopeVersionId(),
            order.expectedBatchCount(),
            completed.barrierId(),
            completed.status(),
            completed.revision()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public BatchSnapshot captureBatch(long batchId) {
        BatchRow batch = lockBatch(batchId);
        startTask(batch.businessTaskId());
        if ("CREATED".equals(batch.status())) {
            updateBatchStatus(batchId, "RUNNING");
        } else if (!"RUNNING".equals(batch.status())
            && !"APPLIED".equals(batch.status())
            && !"CLOSED".equals(batch.status())
            && !"CONFLICT".equals(batch.status())) {
            throw conflict("逻辑合并批次状态不允许冻结: " + batch.status());
        }
        return new BatchSnapshot(
            batchId,
            batch.mergeOrderId(),
            batch.batchNo(),
            batch.status(),
            batch.inputHash(),
            batch.migratedCount(),
            batch.conflictRetainedCount()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public BatchSnapshot checkBatch(long batchId) {
        BatchRow batch = lockBatch(batchId);
        WmsLogicalMergeCommand command = readBatchCommand(batch);
        WmsLogicalMergePlanner.Plan plan = WmsLogicalMergePlanner.plan(command.planCommand());
        assertCurrentAssignments(command.expectedAssignments());
        return new BatchSnapshot(
            batchId,
            batch.mergeOrderId(),
            batch.batchNo(),
            "CHECKED",
            plan.digest(),
            batch.migratedCount(),
            batch.conflictRetainedCount()
        );
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsLogicalMergeResult applyBatch(long batchId) {
        BatchRow batch = lockBatch(batchId);
        if ("APPLIED".equals(batch.status())
            || "CLOSED".equals(batch.status())
            || "CONFLICT".equals(batch.status())) {
            return replayBatch(batch);
        }
        WmsLogicalMergeCommand command = readBatchCommand(batch);
        WmsLogicalMergeResult result = mergeService.apply(command);
        int rows = jdbcTemplate.update("""
            update wms_logical_merge_batch
               set status = 'APPLIED', migrated_count = ?,
                   conflict_retained_count = ?, revision = revision + 1,
                   update_time = current_timestamp(3)
             where batch_id = ? and status in ('RUNNING','CREATED')
            """,
            result.migratedCount(),
            result.conflictRetainedCount(),
            batchId
        );
        if (rows != 1) {
            throw conflict("逻辑合并批次应用发生并发竞争");
        }
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public BatchSnapshot closeBatch(long batchId) {
        BatchRow batch = lockBatch(batchId);
        if ("CLOSED".equals(batch.status()) || "CONFLICT".equals(batch.status())) {
            return new BatchSnapshot(
                batchId, batch.mergeOrderId(), batch.batchNo(), batch.status(),
                batch.inputHash(), batch.migratedCount(), batch.conflictRetainedCount()
            );
        }
        if (!"APPLIED".equals(batch.status())) {
            throw conflict("逻辑合并批次必须先 APPLIED");
        }
        String targetStatus = batch.conflictRetainedCount() > 0 ? "CONFLICT" : "CLOSED";
        updateBatchStatus(batchId, targetStatus);
        completeTask(batch.businessTaskId());
        long open = jdbcTemplate.queryForObject("""
            select count(*) from wms_logical_merge_batch
             where merge_order_id = ? and status not in ('CLOSED','CONFLICT')
            """, Long.class, batch.mergeOrderId());
        if (open == 0) {
            jdbcTemplate.update("""
                update wms_logical_merge_order
                   set status = 'VERIFYING', revision = revision + 1,
                       update_by = ?, update_time = current_timestamp(3)
                 where merge_order_id = ? and status = 'RUNNING'
                """, LoginHelper.getUserId(), batch.mergeOrderId());
        }
        return new BatchSnapshot(
            batchId,
            batch.mergeOrderId(),
            batch.batchNo(),
            targetStatus,
            batch.inputHash(),
            batch.migratedCount(),
            batch.conflictRetainedCount()
        );
    }

    private WmsLogicalMergeResult replayBatch(BatchRow batch) {
        WmsLogicalMergeCommand command = readBatchCommand(batch);
        WmsLogicalMergeResult result = mergeService.apply(command);
        return new WmsLogicalMergeResult(
            result.effectKey(),
            result.inputDigest(),
            result.targetScopeVersionId(),
            result.batches(),
            result.migratedAssignments(),
            batch.migratedCount(),
            result.alreadyTargetCount(),
            batch.conflictRetainedCount(),
            true
        );
    }

    private WmsLogicalMergeCommand readBatchCommand(BatchRow batch) {
        WmsLogicalMergeCommand command = jsonMapper.readValue(
            batch.inputJson(), WmsLogicalMergeCommand.class);
        if (command == null
            || !batch.inputHash().equals(WmsRuleDigest.sha256(batch.inputJson()))
            || command.expectedAssignments() == null
            || command.expectedAssignments().isEmpty()) {
            throw conflict("逻辑合并批次冻结输入无效");
        }
        return command;
    }

    private void assertCurrentAssignments(
        List<WmsLogicalMergePlanner.Assignment> expected
    ) {
        List<Long> ids = expected.stream()
            .map(WmsLogicalMergePlanner.Assignment::inventoryId)
            .sorted()
            .toList();
        List<WmsLogicalMergePlanner.Assignment> current = namedJdbc.query("""
            select assignment.inventory_id, assignment.logical_assignment_id,
                   assignment.scope_version_id, assignment.assignment_revision,
                   version.rule_package_version_id
              from wms_material_logical_assignment assignment
              join wms_logical_scope_version version
                on version.scope_version_id = assignment.scope_version_id
             where assignment.inventory_id in (:ids)
             order by assignment.inventory_id
            """, Map.of("ids", ids), (rs, rowNum) ->
            new WmsLogicalMergePlanner.Assignment(
                rs.getLong("inventory_id"),
                rs.getLong("logical_assignment_id"),
                rs.getLong("scope_version_id"),
                rs.getLong("rule_package_version_id"),
                rs.getInt("assignment_revision")
            ));
        if (!current.equals(expected)) {
            throw conflict(WmsLogicalMergeService.ERROR_ASSIGNMENT_CONFLICT
                + ": 批次归属事实在检查时已变化");
        }
    }

    private ParentRow lockOrder(long mergeOrderId) {
        return jdbcTemplate.query("""
            select merge_order_id, command_key, target_scope_version_id,
                   input_json, status, expected_batch_count, conflict_count,
                   parent_plan_instance_id, parent_business_task_id, barrier_id,
                   reason
              from wms_logical_merge_order
             where merge_order_id = ? for update
            """, (rs, rowNum) -> new ParentRow(
            rs.getLong("merge_order_id"),
            rs.getString("command_key"),
            rs.getLong("target_scope_version_id"),
            rs.getString("input_json"),
            rs.getString("status"),
            rs.getInt("expected_batch_count"),
            rs.getInt("conflict_count"),
            nullableLong(rs, "parent_plan_instance_id"),
            nullableLong(rs, "parent_business_task_id"),
            nullableLong(rs, "barrier_id"),
            rs.getString("reason")
        ), mergeOrderId).stream().findFirst().orElseThrow(() ->
            new ServiceException("逻辑合并父单不存在: " + mergeOrderId));
    }

    private BatchRow lockBatch(long batchId) {
        return jdbcTemplate.query("""
            select batch_id, merge_order_id, batch_no, task_key, plan_instance_id,
                   business_task_id, input_hash, input_json, status, migrated_count,
                   conflict_retained_count, revision
              from wms_logical_merge_batch
             where batch_id = ? for update
            """, (rs, rowNum) -> new BatchRow(
            rs.getLong("batch_id"),
            rs.getLong("merge_order_id"),
            rs.getInt("batch_no"),
            rs.getString("task_key"),
            rs.getLong("plan_instance_id"),
            rs.getLong("business_task_id"),
            rs.getString("input_hash"),
            rs.getString("input_json"),
            rs.getString("status"),
            rs.getInt("migrated_count"),
            rs.getInt("conflict_retained_count"),
            rs.getInt("revision")
        ), batchId).stream().findFirst().orElseThrow(() ->
            new ServiceException("逻辑合并批次不存在: " + batchId));
    }

    private List<BatchRow> batches(long mergeOrderId) {
        return jdbcTemplate.query("""
            select batch_id, merge_order_id, batch_no, task_key, plan_instance_id,
                   business_task_id, input_hash, input_json, status, migrated_count,
                   conflict_retained_count, revision
              from wms_logical_merge_batch
             where merge_order_id = ? order by batch_no
            """, (rs, rowNum) -> new BatchRow(
            rs.getLong("batch_id"),
            rs.getLong("merge_order_id"),
            rs.getInt("batch_no"),
            rs.getString("task_key"),
            rs.getLong("plan_instance_id"),
            rs.getLong("business_task_id"),
            rs.getString("input_hash"),
            rs.getString("input_json"),
            rs.getString("status"),
            rs.getInt("migrated_count"),
            rs.getInt("conflict_retained_count"),
            rs.getInt("revision")
        ), mergeOrderId);
    }

    private int totalConflictCount(long mergeOrderId) {
        Integer value = jdbcTemplate.queryForObject("""
            select coalesce(sum(conflict_retained_count), 0)
              from wms_logical_merge_batch where merge_order_id = ?
            """, Integer.class, mergeOrderId);
        return value == null ? 0 : value;
    }

    private void startTask(Long taskId) {
        if (taskId == null) {
            throw conflict("逻辑合并业务任务身份缺失");
        }
        WmsBusinessTaskSnapshot task = taskService.get(taskId);
        if ("ACTIVE".equals(task.status())) {
            taskService.transition(new WmsBusinessTaskTransitionCommand(
                task.taskId(), task.revision(), "RUNNING",
                LoginHelper.getUserId(), LoginHelper.getDeptId()
            ));
        }
    }

    private void completeTask(Long taskId) {
        if (taskId == null) {
            throw conflict("逻辑合并业务任务身份缺失");
        }
        WmsBusinessTaskSnapshot task = taskService.get(taskId);
        if ("RUNNING".equals(task.status())) {
            taskService.transition(new WmsBusinessTaskTransitionCommand(
                task.taskId(), task.revision(), "COMPLETED",
                LoginHelper.getUserId(), LoginHelper.getDeptId()
            ));
        }
    }

    private void updateBatchStatus(long batchId, String status) {
        int rows = jdbcTemplate.update("""
            update wms_logical_merge_batch
               set status = ?, revision = revision + 1,
                   update_time = current_timestamp(3)
             where batch_id = ? and status <> ?
            """, status, batchId, status);
        if (rows != 1) {
            throw conflict("逻辑合并批次状态迁移竞争");
        }
    }

    private ParentSnapshot parentSnapshot(ParentRow order) {
        WmsBusinessTaskBarrierSnapshot barrier = barrierService.get(
            WmsLogicalMergePlanContract.BUSINESS_TYPE,
            order.mergeOrderId(),
            WmsLogicalMergePlanContract.BARRIER_KEY
        );
        return new ParentSnapshot(
            order.mergeOrderId(), order.targetScopeVersionId(),
            order.expectedBatchCount(), barrier.barrierId(),
            barrier.status(), barrier.revision()
        );
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
        throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static ServiceException conflict(String message) {
        return new ServiceException(message);
    }

    public record ParentSnapshot(
        long mergeOrderId,
        long targetScopeVersionId,
        int expectedBatchCount,
        long barrierId,
        String barrierStatus,
        int barrierRevision
    ) {
    }

    public record BatchSnapshot(
        long batchId,
        long mergeOrderId,
        int batchNo,
        String status,
        String inputDigest,
        int migratedCount,
        int conflictRetainedCount
    ) {
    }

    private record ParentRow(
        long mergeOrderId,
        String commandKey,
        long targetScopeVersionId,
        String inputJson,
        String status,
        int expectedBatchCount,
        int conflictCount,
        Long parentPlanInstanceId,
        Long parentBusinessTaskId,
        Long barrierId,
        String reason
    ) {
    }

    private record BatchRow(
        long batchId,
        long mergeOrderId,
        int batchNo,
        String taskKey,
        long planInstanceId,
        long businessTaskId,
        String inputHash,
        String inputJson,
        String status,
        int migratedCount,
        int conflictRetainedCount,
        int revision
    ) {
    }
}
