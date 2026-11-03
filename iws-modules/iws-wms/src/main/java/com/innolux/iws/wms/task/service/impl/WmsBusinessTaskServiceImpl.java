/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 以唯一业务键、实例和修订号持久化任务冻结输入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.service.impl;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskSnapshot;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskTransitionCommand;
import com.innolux.iws.wms.task.service.WmsBusinessTaskService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 业务任务持久化实现。 */
@Service
public class WmsBusinessTaskServiceImpl implements WmsBusinessTaskService {

    private static final Set<String> TERMINAL_STATUSES =
        Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final JdbcTemplate jdbcTemplate;

    private final IdentifierGenerator identifierGenerator;

    private final JsonMapper jsonMapper;

    public WmsBusinessTaskServiceImpl(
        JdbcTemplate jdbcTemplate,
        IdentifierGenerator identifierGenerator,
        JsonMapper jsonMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.identifierGenerator = identifierGenerator;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsBusinessTaskSnapshot createOrReplay(WmsBusinessTaskCreateCommand command) {
        validateCreate(command);
        WmsBusinessTaskSnapshot existing = findByBusinessKey(
            command.businessType(), command.taskKey());
        if (existing == null) {
            existing = findByPlanInstance(command.planInstanceId());
        }
        if (existing != null) {
            return replay(existing, command);
        }
        Number generated = identifierGenerator.nextId(null);
        if (generated == null) {
            throw new ServiceException("业务任务主键生成失败");
        }
        long taskId = generated.longValue();
        try {
            jdbcTemplate.update("""
                insert into wms_business_task
                    (task_id, business_type, business_id, line_id, allocation_unit_id,
                     round_no, phase, task_key, plan_type, plan_instance_id,
                     input_hash, input_json, status, revision,
                     create_dept, create_by, update_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?)
                """,
                taskId,
                command.businessType(),
                command.businessId(),
                command.lineId(),
                command.allocationUnitId(),
                command.roundNo(),
                command.phase(),
                command.taskKey(),
                command.planType(),
                command.planInstanceId(),
                command.inputHash(),
                command.inputJson(),
                command.departmentId(),
                command.actorId(),
                command.actorId()
            );
        } catch (DuplicateKeyException conflict) {
            WmsBusinessTaskSnapshot raced = findByBusinessKeyCurrent(
                command.businessType(), command.taskKey());
            if (raced == null) {
                raced = findByPlanInstanceCurrent(command.planInstanceId());
            }
            if (raced == null) {
                throw conflict;
            }
            return replay(raced, command);
        }
        return require(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsBusinessTaskSnapshot transition(WmsBusinessTaskTransitionCommand command) {
        if (command == null || command.taskId() <= 0 || command.expectedRevision() <= 0) {
            throw new ServiceException("业务任务迁移命令缺少有效任务或修订号");
        }
        String target = command.targetStatus();
        if (!Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED").contains(target)) {
            throw new ServiceException("不支持的业务任务目标状态: " + target);
        }
        WmsBusinessTaskSnapshot current = requireForUpdate(command.taskId());
        if (current.revision() != command.expectedRevision()) {
            throw new ServiceException("业务任务修订号冲突");
        }
        requireTransition(current.status(), target);
        int rows = jdbcTemplate.update("""
            update wms_business_task
               set status = ?, revision = revision + 1, update_by = ?,
                   update_time = current_timestamp(3)
             where task_id = ? and revision = ? and status = ?
            """,
            target,
            command.actorId(),
            command.taskId(),
            command.expectedRevision(),
            current.status()
        );
        if (rows != 1) {
            throw new ServiceException("业务任务状态迁移竞争失败");
        }
        return require(command.taskId());
    }

    @Override
    public WmsBusinessTaskSnapshot get(long taskId) {
        return require(taskId);
    }

    private WmsBusinessTaskSnapshot replay(
        WmsBusinessTaskSnapshot existing,
        WmsBusinessTaskCreateCommand command
    ) {
        boolean same = Objects.equals(existing.businessType(), command.businessType())
            && existing.businessId() == command.businessId()
            && Objects.equals(existing.taskKey(), command.taskKey())
            && Objects.equals(existing.planType(), command.planType())
            && existing.planInstanceId() == command.planInstanceId()
            && Objects.equals(existing.inputHash(), command.inputHash())
            && Objects.equals(existing.inputJson(), command.inputJson());
        if (!same) {
            throw new ServiceException("业务任务稳定键已被不同身份或冻结输入占用");
        }
        return existing;
    }

    private void validateCreate(WmsBusinessTaskCreateCommand command) {
        if (command == null) {
            throw new ServiceException("业务任务创建命令不能为空");
        }
        requireText(command.businessType(), 64, "业务类型");
        requireText(command.phase(), 32, "任务阶段");
        requireText(command.taskKey(), 96, "任务键");
        requireText(command.planType(), 64, "计划类型");
        if (command.businessId() <= 0 || command.allocationUnitId() <= 0
            || command.planInstanceId() <= 0) {
            throw new ServiceException("业务任务身份ID必须大于零");
        }
        if (command.lineId() != null && command.lineId() <= 0) {
            throw new ServiceException("业务任务明细ID必须大于零");
        }
        if (command.roundNo() < 0) {
            throw new ServiceException("业务任务轮次不能小于零");
        }
        if (command.inputHash() == null
            || !command.inputHash().matches("[0-9a-f]{64}")) {
            throw new ServiceException("业务任务输入摘要必须为64位小写十六进制");
        }
        if (command.inputJson() == null || command.inputJson().isBlank()) {
            throw new ServiceException("业务任务冻结输入不能为空");
        }
        JsonNode tree = jsonMapper.readTree(command.inputJson());
        if (tree == null || !tree.isObject()) {
            throw new ServiceException("业务任务冻结输入必须为JSON对象");
        }
    }

    private static void requireText(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new ServiceException("业务任务" + label + "为空或超长");
        }
    }

    private static void requireTransition(String current, String target) {
        if (TERMINAL_STATUSES.contains(current)) {
            throw new ServiceException("终态业务任务不可再迁移: " + current);
        }
        boolean allowed = switch (current) {
            case "ACTIVE" -> Set.of("RUNNING", "FAILED", "CANCELLED").contains(target);
            case "RUNNING" -> Set.of("COMPLETED", "FAILED", "CANCELLED").contains(target);
            default -> false;
        };
        if (!allowed) {
            throw new ServiceException("业务任务状态迁移不允许: " + current + " -> " + target);
        }
    }

    private WmsBusinessTaskSnapshot findByBusinessKey(String businessType, String taskKey) {
        List<WmsBusinessTaskSnapshot> rows = jdbcTemplate.query("""
            select task_id, business_type, business_id, task_key, plan_type,
                   plan_instance_id, input_hash, input_json, status, revision
              from wms_business_task
             where business_type = ? and task_key = ?
            """, (result, row) -> snapshot(result), businessType, taskKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WmsBusinessTaskSnapshot findByPlanInstance(long planInstanceId) {
        List<WmsBusinessTaskSnapshot> rows = jdbcTemplate.query("""
            select task_id, business_type, business_id, task_key, plan_type,
                   plan_instance_id, input_hash, input_json, status, revision
              from wms_business_task
             where plan_instance_id = ?
            """, (result, row) -> snapshot(result), planInstanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WmsBusinessTaskSnapshot findByBusinessKeyCurrent(String businessType, String taskKey) {
        List<WmsBusinessTaskSnapshot> rows = jdbcTemplate.query("""
            select task_id, business_type, business_id, task_key, plan_type,
                   plan_instance_id, input_hash, input_json, status, revision
              from wms_business_task
             where business_type = ? and task_key = ?
               for update
            """, (result, row) -> snapshot(result), businessType, taskKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WmsBusinessTaskSnapshot findByPlanInstanceCurrent(long planInstanceId) {
        List<WmsBusinessTaskSnapshot> rows = jdbcTemplate.query("""
            select task_id, business_type, business_id, task_key, plan_type,
                   plan_instance_id, input_hash, input_json, status, revision
              from wms_business_task
             where plan_instance_id = ?
               for update
            """, (result, row) -> snapshot(result), planInstanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WmsBusinessTaskSnapshot require(long taskId) {
        if (taskId <= 0) {
            throw new ServiceException("业务任务ID必须大于零");
        }
        List<WmsBusinessTaskSnapshot> rows = jdbcTemplate.query("""
            select task_id, business_type, business_id, task_key, plan_type,
                   plan_instance_id, input_hash, input_json, status, revision
              from wms_business_task
             where task_id = ?
            """, (result, row) -> snapshot(result), taskId);
        if (rows.isEmpty()) {
            throw new ServiceException("业务任务不存在: " + taskId);
        }
        return rows.get(0);
    }

    private WmsBusinessTaskSnapshot requireForUpdate(long taskId) {
        List<WmsBusinessTaskSnapshot> rows = jdbcTemplate.query("""
            select task_id, business_type, business_id, task_key, plan_type,
                   plan_instance_id, input_hash, input_json, status, revision
              from wms_business_task
             where task_id = ?
               for update
            """, (result, row) -> snapshot(result), taskId);
        if (rows.isEmpty()) {
            throw new ServiceException("业务任务不存在: " + taskId);
        }
        return rows.get(0);
    }

    private static WmsBusinessTaskSnapshot snapshot(java.sql.ResultSet result)
        throws java.sql.SQLException {
        return new WmsBusinessTaskSnapshot(
            result.getLong("task_id"),
            result.getString("business_type"),
            result.getLong("business_id"),
            result.getString("task_key"),
            result.getString("plan_type"),
            result.getLong("plan_instance_id"),
            result.getString("input_hash"),
            result.getString("input_json"),
            result.getString("status"),
            result.getInt("revision")
        );
    }
}
