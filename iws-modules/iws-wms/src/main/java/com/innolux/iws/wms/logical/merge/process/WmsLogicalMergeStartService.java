/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 冻结父范围和全部固定批次并挂载父、子持久计划；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.process;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.process.api.AttachResult;
import com.innolux.iws.process.api.AttachTaskCommand;
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.api.CommandResult;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.api.PlanControlCommand;
import com.innolux.iws.process.api.PlanTypeVo;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.service.BusinessScheduleService;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.service.PlanInstanceService;
import com.innolux.iws.wms.logical.merge.WmsLogicalMergePlanner;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeStartCommand;
import com.innolux.iws.wms.logical.merge.domain.WmsLogicalMergeStartResult;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.service.WmsBusinessTaskBarrierService;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 逻辑合并父单受理服务。 */
@Service
public class WmsLogicalMergeStartService {

    public static final String PERMISSION_MANAGE = "inventory:logical-merge:manage";

    private static final String PROCESS_ENABLED = "iws.process.enabled";

    private final Environment environment;

    private final ObjectProvider<BusinessScheduleService> scheduleProvider;

    private final ObjectProvider<PlanDefinitionService> definitionProvider;

    private final ObjectProvider<PlanInstanceService> instanceProvider;

    private final WmsBusinessTaskService taskService;

    private final WmsBusinessTaskBarrierService barrierService;

    private final ProcessInstanceMapper instanceMapper;

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final IdentifierGenerator identifierGenerator;

    private final JsonMapper jsonMapper;

    public WmsLogicalMergeStartService(
        Environment environment,
        ObjectProvider<BusinessScheduleService> scheduleProvider,
        ObjectProvider<PlanDefinitionService> definitionProvider,
        ObjectProvider<PlanInstanceService> instanceProvider,
        WmsBusinessTaskService taskService,
        WmsBusinessTaskBarrierService barrierService,
        ProcessInstanceMapper instanceMapper,
        JdbcTemplate jdbcTemplate,
        IdentifierGenerator identifierGenerator,
        JsonMapper jsonMapper
    ) {
        this.environment = environment;
        this.scheduleProvider = scheduleProvider;
        this.definitionProvider = definitionProvider;
        this.instanceProvider = instanceProvider;
        this.taskService = taskService;
        this.barrierService = barrierService;
        this.instanceMapper = instanceMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.identifierGenerator = identifierGenerator;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsLogicalMergeStartResult start(WmsLogicalMergeStartCommand source) {
        if (!Boolean.TRUE.equals(
            environment.getProperty(PROCESS_ENABLED, Boolean.class, Boolean.FALSE)
        )) {
            throw new ServiceException("计划事件平台未启用，拒绝逻辑合并");
        }
        WmsLogicalMergeStartCommand command = normalize(source);
        String inputJson = jsonMapper.writeValueAsString(command);
        String inputHash = WmsRuleDigest.sha256(inputJson);
        Map<String, Object> existing = jdbcTemplate.query("""
            select merge_order_id, input_hash from wms_logical_merge_order
             where command_key = ?
            """, (rs, rowNum) -> Map.<String, Object>of(
                "mergeOrderId", rs.getLong("merge_order_id"),
                "inputHash", rs.getString("input_hash")
            ), command.commandKey()).stream().findFirst().orElse(null);
        if (existing != null) {
            if (!inputHash.equals(existing.get("inputHash"))) {
                throw new ServiceException("同一命令键已绑定不同逻辑合并输入");
            }
            return replay(((Number) existing.get("mergeOrderId")).longValue());
        }

        TargetRow target = loadTarget(command.targetScopeVersionId());
        List<WmsLogicalMergePlanner.Assignment> assignments =
            loadAssignments(command.sourceScopeVersionIds());
        if (assignments.isEmpty()) {
            throw new ServiceException("逻辑合并范围内没有物料归属");
        }
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
                assignments
            )
        );
        BusinessScheduleVo schedule = findSchedule();
        requirePlanType(schedule, WmsLogicalMergePlanContract.BATCH_PLAN_TYPE);
        requirePlanType(schedule, WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE);
        verifyDefinitions();

        long mergeOrderId = nextId();
        try {
            jdbcTemplate.update("""
                insert into wms_logical_merge_order
                    (merge_order_id, command_key, target_scope_version_id,
                     source_scope_version_ids_json, compatible_scope_version_ids_json,
                     batch_size, input_hash, input_json, status, expected_batch_count,
                     conflict_count, revision, reason, create_dept, create_by,
                     update_by, create_time, update_time)
                values (?, ?, ?, cast(? as json), cast(? as json), ?, ?, ?,
                        'BATCHING', ?, 0, 1, ?, ?, ?, ?, current_timestamp(3),
                        current_timestamp(3))
                """,
                mergeOrderId,
                command.commandKey(),
                command.targetScopeVersionId(),
                jsonMapper.writeValueAsString(
                    command.sourceScopeVersionIds().stream().sorted().toList()),
                jsonMapper.writeValueAsString(
                    command.compatibleSourceScopeVersionIds().stream().sorted().toList()),
                command.batchSize(),
                inputHash,
                inputJson,
                plan.batches().size(),
                command.reason(),
                LoginHelper.getDeptId(),
                LoginHelper.getUserId(),
                LoginHelper.getUserId()
            );
        } catch (DuplicateKeyException conflict) {
            Map<String, Object> raced = jdbcTemplate.query("""
                select merge_order_id, input_hash from wms_logical_merge_order
                 where command_key = ? for update
                """, (rs, rowNum) -> Map.<String, Object>of(
                    "mergeOrderId", rs.getLong("merge_order_id"),
                    "inputHash", rs.getString("input_hash")
                ), command.commandKey()).stream().findFirst().orElse(null);
            if (raced == null) {
                throw conflict;
            }
            if (!inputHash.equals(raced.get("inputHash"))) {
                throw new ServiceException("同一命令键已并发绑定不同逻辑合并输入");
            }
            return replay(((Number) raced.get("mergeOrderId")).longValue());
        }

        Map<Long, WmsLogicalMergePlanner.Assignment> assignmentById = new LinkedHashMap<>();
        assignments.forEach(item -> assignmentById.put(item.inventoryId(), item));
        List<WmsLogicalMergeStartResult.Batch> batches = new ArrayList<>();
        long scheduleId = parsePositiveLong(schedule.id(), "scheduleId");
        PlanInstanceService instances = require(instanceProvider, "计划实例服务");
        for (WmsLogicalMergePlanner.Batch plannedBatch : plan.batches()) {
            long batchId = nextId();
            String taskKey = taskKey(command.commandKey(), plannedBatch.batchNo());
            List<WmsLogicalMergePlanner.Assignment> batchAssignments =
                plannedBatch.decisions().stream()
                    .map(item -> assignmentById.get(item.inventoryId()))
                    .toList();
            WmsLogicalMergeCommand batchCommand = new WmsLogicalMergeCommand(
                "WMS:" + WmsRuleDigest.sha256(
                    "logical-merge-batch/1\n" + command.commandKey() + "\n"
                        + plannedBatch.batchNo()),
                new WmsLogicalMergePlanner.TargetScope(
                    target.scopeVersionId(),
                    target.rulePackageVersionId(),
                    target.lifecycleStatus(),
                    target.currentVersionId(),
                    target.spaceSnapshotDigest()
                ),
                command.sourceScopeVersionIds(),
                command.compatibleSourceScopeVersionIds(),
                batchAssignments.size(),
                batchAssignments,
                taskKey,
                null,
                LocalDateTime.now(),
                command.reason(),
                LoginHelper.getUserId(),
                LoginHelper.getUsername(),
                LoginHelper.getDeptId(),
                LoginHelper.getDeptName()
            );
            String batchJson = jsonMapper.writeValueAsString(batchCommand);
            String batchHash = WmsRuleDigest.sha256(batchJson);
            AttachResult attached = instances.attach(new AttachTaskCommand(
                scheduleId,
                WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
                WmsLogicalMergePlanContract.BUSINESS_TYPE,
                String.valueOf(batchId),
                taskKey,
                true,
                batchHash,
                List.of(),
                "LOGICAL_MERGE_ATTACH:" + taskKey
            ));
            long instanceId = parsePositiveLong(attached.instanceId(), "instanceId");
            WmsBusinessTaskSnapshot task = taskService.createOrReplay(
                new WmsBusinessTaskCreateCommand(
                    WmsLogicalMergePlanContract.BUSINESS_TYPE,
                    mergeOrderId,
                    batchId,
                    mergeOrderId,
                    plannedBatch.batchNo(),
                    "APPLY",
                    taskKey,
                    WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
                    instanceId,
                    batchHash,
                    batchJson,
                    LoginHelper.getUserId(),
                    LoginHelper.getDeptId()
                )
            );
            jdbcTemplate.update("""
                insert into wms_logical_merge_batch
                    (batch_id, merge_order_id, batch_no, task_key, plan_instance_id,
                     business_task_id, input_hash, input_json, status, migrated_count,
                     conflict_retained_count, revision, create_time, update_time)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', 0, 0, 1,
                        current_timestamp(3), current_timestamp(3))
                """,
                batchId, mergeOrderId, plannedBatch.batchNo(), taskKey, instanceId,
                task.taskId(), batchHash, batchJson
            );
            releaseStart(instances, attached, instanceId, taskKey, command.reason());
            batches.add(new WmsLogicalMergeStartResult.Batch(
                batchId, plannedBatch.batchNo(), taskKey, instanceId, task.taskId()));
        }

        AttachResult parent = instances.attach(new AttachTaskCommand(
            scheduleId,
            WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
            WmsLogicalMergePlanContract.BUSINESS_TYPE,
            String.valueOf(mergeOrderId),
            command.commandKey(),
            true,
            inputHash,
            List.of(),
            "LOGICAL_MERGE_CLOSE_ATTACH:" + command.commandKey()
        ));
        long parentInstanceId = parsePositiveLong(parent.instanceId(), "parentInstanceId");
        WmsBusinessTaskSnapshot parentTask = taskService.createOrReplay(
            new WmsBusinessTaskCreateCommand(
                WmsLogicalMergePlanContract.BUSINESS_TYPE,
                mergeOrderId,
                null,
                mergeOrderId,
                0,
                "CLOSE",
                command.commandKey(),
                WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
                parentInstanceId,
                inputHash,
                inputJson,
                LoginHelper.getUserId(),
                LoginHelper.getDeptId()
            )
        );
        List<String> expectedTaskKeys = batches.stream()
            .map(WmsLogicalMergeStartResult.Batch::taskKey)
            .toList();
        WmsBusinessTaskBarrierSnapshot barrier = barrierService.createOrReplay(
            new WmsBusinessTaskBarrierCreateCommand(
                WmsLogicalMergePlanContract.BUSINESS_TYPE,
                mergeOrderId,
                WmsLogicalMergePlanContract.BARRIER_KEY,
                expectedTaskKeys,
                LoginHelper.getUserId(),
                LoginHelper.getDeptId()
            )
        );
        releaseStart(instances, parent, parentInstanceId, command.commandKey(), command.reason());
        int rows = jdbcTemplate.update("""
            update wms_logical_merge_order
               set status = 'RUNNING', parent_plan_instance_id = ?,
                   parent_business_task_id = ?, barrier_id = ?,
                   revision = revision + 1, update_by = ?,
                   update_time = current_timestamp(3)
             where merge_order_id = ? and status = 'BATCHING'
            """,
            parentInstanceId,
            parentTask.taskId(),
            barrier.barrierId(),
            LoginHelper.getUserId(),
            mergeOrderId
        );
        if (rows != 1) {
            throw new ServiceException("逻辑合并父计划身份写入失败");
        }
        return new WmsLogicalMergeStartResult(
            mergeOrderId,
            parentInstanceId,
            parentTask.taskId(),
            barrier.barrierId(),
            inputHash,
            batches
        );
    }

    private WmsLogicalMergeStartResult replay(long mergeOrderId) {
        Map<String, Object> order = jdbcTemplate.queryForMap("""
            select input_hash, parent_plan_instance_id, parent_business_task_id, barrier_id
              from wms_logical_merge_order where merge_order_id = ?
            """, mergeOrderId);
        List<WmsLogicalMergeStartResult.Batch> batches = jdbcTemplate.query("""
            select batch_id, batch_no, task_key, plan_instance_id, business_task_id
              from wms_logical_merge_batch
             where merge_order_id = ? order by batch_no
            """, (rs, rowNum) -> new WmsLogicalMergeStartResult.Batch(
            rs.getLong("batch_id"),
            rs.getInt("batch_no"),
            rs.getString("task_key"),
            rs.getLong("plan_instance_id"),
            rs.getLong("business_task_id")
        ), mergeOrderId);
        return new WmsLogicalMergeStartResult(
            mergeOrderId,
            ((Number) order.get("parent_plan_instance_id")).longValue(),
            ((Number) order.get("parent_business_task_id")).longValue(),
            ((Number) order.get("barrier_id")).longValue(),
            String.valueOf(order.get("input_hash")),
            batches
        );
    }

    private WmsLogicalMergeStartCommand normalize(WmsLogicalMergeStartCommand source) {
        if (source == null || source.commandKey() == null || source.commandKey().isBlank()
            || source.commandKey().length() > 96) {
            throw new ServiceException("逻辑合并 commandKey 必须为 1 到 96 个字符");
        }
        if (source.targetScopeVersionId() == null || source.targetScopeVersionId() <= 0
            || source.sourceScopeVersionIds() == null
            || source.sourceScopeVersionIds().isEmpty()
            || source.compatibleSourceScopeVersionIds() == null
            || source.batchSize() == null
            || source.batchSize() < 1
            || source.batchSize() > WmsLogicalMergePlanner.MAX_BATCH_SIZE
            || source.reason() == null
            || source.reason().isBlank()
            || source.reason().length() > 500) {
            throw new ServiceException("逻辑合并目标、来源、批大小或原因非法");
        }
        if (!source.sourceScopeVersionIds()
            .containsAll(source.compatibleSourceScopeVersionIds())) {
            throw new ServiceException("兼容范围必须包含在固定来源范围内");
        }
        return source;
    }

    private TargetRow loadTarget(long scopeVersionId) {
        return jdbcTemplate.query("""
            select version.scope_version_id, version.rule_package_version_id,
                   version.space_snapshot_digest, version.status,
                   scope.lifecycle_status, scope.current_version_id
              from wms_logical_scope_version version
              join wms_logical_scope scope
                on scope.logical_scope_id = version.logical_scope_id
              join wms_rule_package_version package
                on package.package_version_id = version.rule_package_version_id
             where version.scope_version_id = ?
               and version.status = 'ACTIVE'
               and scope.lifecycle_status = 'ACTIVE'
               and scope.current_version_id = version.scope_version_id
               and package.status = 'ACTIVE'
            """, (rs, rowNum) -> new TargetRow(
            rs.getLong("scope_version_id"),
            rs.getLong("rule_package_version_id"),
            rs.getLong("current_version_id"),
            rs.getString("lifecycle_status"),
            rs.getString("space_snapshot_digest")
        ), scopeVersionId).stream().findFirst().orElseThrow(() ->
            new ServiceException(
                WmsLogicalMergePlanner.ERROR_TARGET_NOT_EFFECTIVE
                    + ": 目标逻辑作用域不是唯一当前生效版本"
            ));
    }

    private List<WmsLogicalMergePlanner.Assignment> loadAssignments(Set<Long> sourceIds) {
        long sourceCount = namedJdbc.queryForObject("""
            select count(*) from wms_logical_scope_version
             where scope_version_id in (:ids) and status = 'ACTIVE'
            """, Map.of("ids", sourceIds), Long.class);
        if (sourceCount != sourceIds.size()) {
            throw new ServiceException("固定来源范围包含不存在或非 ACTIVE 的版本");
        }
        return namedJdbc.query("""
            select assignment.inventory_id, assignment.logical_assignment_id,
                   assignment.scope_version_id, assignment.assignment_revision,
                   version.rule_package_version_id
              from wms_material_logical_assignment assignment
              join wms_logical_scope_version version
                on version.scope_version_id = assignment.scope_version_id
             where assignment.scope_version_id in (:ids)
             order by assignment.inventory_id
            """, Map.of("ids", sourceIds), (rs, rowNum) ->
            new WmsLogicalMergePlanner.Assignment(
                rs.getLong("inventory_id"),
                rs.getLong("logical_assignment_id"),
                rs.getLong("scope_version_id"),
                rs.getLong("rule_package_version_id"),
                rs.getInt("assignment_revision")
            ));
    }

    private BusinessScheduleVo findSchedule() {
        BusinessScheduleService schedules = require(scheduleProvider, "业务调度服务");
        PageResult<BusinessScheduleVo> page = schedules.page(
            WmsLogicalMergePlanContract.SCHEDULE_CODE,
            true,
            new PageQuery(50, 1)
        );
        List<BusinessScheduleVo> matches = page.getRows().stream()
            .filter(item -> WmsLogicalMergePlanContract.SCHEDULE_CODE.equals(item.businessCode()))
            .toList();
        if (matches.size() != 1) {
            throw new ServiceException("逻辑合并业务调度必须唯一且已启用");
        }
        return matches.getFirst();
    }

    private void requirePlanType(BusinessScheduleVo schedule, String planType) {
        PlanTypeVo type = schedule.planTypes() == null ? null : schedule.planTypes().stream()
            .filter(item -> planType.equals(item.planType()))
            .findFirst()
            .orElse(null);
        if (type == null || !Boolean.TRUE.equals(type.enabled())) {
            throw new ServiceException("逻辑合并计划类型未启用: " + planType);
        }
    }

    private void verifyDefinitions() {
        PlanDefinitionService definitions = require(definitionProvider, "计划定义服务");
        verifyDefinition(definitions,
            WmsLogicalMergePlanContract.BATCH_PLAN_CODE,
            WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
            "BUSINESS");
        verifyDefinition(definitions,
            WmsLogicalMergePlanContract.BATCH_SUPERVISOR_PLAN_CODE,
            WmsLogicalMergePlanContract.BATCH_PLAN_TYPE,
            "SUPERVISOR");
        verifyDefinition(definitions,
            WmsLogicalMergePlanContract.CLOSE_PLAN_CODE,
            WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
            "BUSINESS");
        verifyDefinition(definitions,
            WmsLogicalMergePlanContract.CLOSE_SUPERVISOR_PLAN_CODE,
            WmsLogicalMergePlanContract.CLOSE_PLAN_TYPE,
            "SUPERVISOR");
    }

    private void verifyDefinition(
        PlanDefinitionService definitions,
        String planCode,
        String planType,
        String role
    ) {
        PageResult<DefinitionSummaryVo> page = definitions.page(
            planCode, "ACTIVE", role, new PageQuery(50, 1));
        List<DefinitionSummaryVo> matches = page.getRows().stream()
            .filter(item -> planCode.equals(item.planCode()))
            .filter(item -> planType.equals(item.planType()))
            .toList();
        if (matches.size() != 1) {
            throw new ServiceException("逻辑合并计划生效版本不唯一: " + planCode);
        }
    }

    private void releaseStart(
        PlanInstanceService instances,
        AttachResult attached,
        long instanceId,
        String suffix,
        String reason
    ) {
        var instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new ServiceException("逻辑合并计划实例不存在: " + instanceId);
        }
        long expectedVersion = attached.instanceVersion() == null
            ? instance.getInstanceVersion()
            : parsePositiveLong(attached.instanceVersion(), "instanceVersion");
        CommandResult released = instances.releaseStart(new PlanControlCommand(
            instanceId,
            expectedVersion,
            "LOGICAL_MERGE_RELEASE:" + suffix,
            reason
        ));
        if (released == null || !"SUCCEEDED".equals(released.code())) {
            throw new ServiceException("逻辑合并计划解除挂起失败");
        }
    }

    private String taskKey(String commandKey, int batchNo) {
        String suffix = ":B" + batchNo;
        int length = Math.min(commandKey.length(), 96 - suffix.length());
        return commandKey.substring(0, length) + suffix;
    }

    private long nextId() {
        Number id = identifierGenerator.nextId(null);
        if (id == null) {
            throw new IllegalStateException("逻辑合并主键生成失败");
        }
        return id.longValue();
    }

    private static <T> T require(ObjectProvider<T> provider, String name) {
        T value = provider.getIfAvailable();
        if (value == null) {
            throw new ServiceException(name + " 未启用或不可用");
        }
        return value;
    }

    private static long parsePositiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new ServiceException(name + " 必须为正整数");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new ServiceException(name + " 必须为数字: " + value);
        }
    }

    private record TargetRow(
        long scopeVersionId,
        long rulePackageVersionId,
        long currentVersionId,
        String lifecycleStatus,
        String spaceSnapshotDigest
    ) {
    }
}
