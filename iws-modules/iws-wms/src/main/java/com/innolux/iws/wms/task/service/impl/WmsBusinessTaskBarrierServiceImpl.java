/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 业务任务
 * @Version      : V1.0
 * @Description  : 在锁内核对预期任务集合并通过CAS完成父业务屏障；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.task.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCompleteCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierCreateCommand;
import com.innolux.iws.wms.task.domain.WmsBusinessTaskBarrierSnapshot;
import com.innolux.iws.wms.task.service.WmsBusinessTaskBarrierService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 业务任务完成屏障持久化实现。 */
@Service
public class WmsBusinessTaskBarrierServiceImpl implements WmsBusinessTaskBarrierService {

    private static final int MAX_TASKS = 200;

    private final JdbcTemplate jdbcTemplate;

    private final IdentifierGenerator identifierGenerator;

    private final JsonMapper jsonMapper;

    public WmsBusinessTaskBarrierServiceImpl(
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
    public WmsBusinessTaskBarrierSnapshot createOrReplay(
        WmsBusinessTaskBarrierCreateCommand command
    ) {
        List<String> expected = validateCreate(command);
        WmsBusinessTaskBarrierSnapshot existing = findForUpdate(
            command.businessType(), command.businessId(), command.barrierKey());
        if (existing != null) {
            return replay(existing, expected);
        }
        Number generated = identifierGenerator.nextId(null);
        if (generated == null || generated.longValue() <= 0) {
            throw new ServiceException("业务任务屏障主键生成失败");
        }
        try {
            jdbcTemplate.update("""
                insert into wms_business_task_barrier
                    (barrier_id, business_type, business_id, barrier_key,
                     expected_task_keys_json, status, revision,
                     create_dept, create_by, update_by)
                values (?, ?, ?, ?, ?, 'OPEN', 1, ?, ?, ?)
                """,
                generated.longValue(),
                command.businessType(),
                command.businessId(),
                command.barrierKey(),
                jsonMapper.writeValueAsString(expected),
                command.departmentId(),
                command.actorId(),
                command.actorId()
            );
        } catch (DuplicateKeyException conflict) {
            WmsBusinessTaskBarrierSnapshot raced = findForUpdate(
                command.businessType(), command.businessId(), command.barrierKey());
            if (raced == null) {
                throw conflict;
            }
            return replay(raced, expected);
        }
        return require(command.businessType(), command.businessId(), command.barrierKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsBusinessTaskBarrierSnapshot complete(
        WmsBusinessTaskBarrierCompleteCommand command
    ) {
        validateComplete(command);
        WmsBusinessTaskBarrierSnapshot current = findForUpdate(
            command.businessType(), command.businessId(), command.barrierKey());
        if (current == null) {
            throw error(ERROR_BARRIER_CONFLICT, "业务任务屏障不存在", HttpStatus.HTTP_NOT_FOUND);
        }
        if ("COMPLETED".equals(current.status())) {
            return current;
        }
        if (current.revision() != command.expectedRevision()) {
            throw error(ERROR_BARRIER_CONFLICT, "业务任务屏障修订号冲突",
                HttpStatus.HTTP_CONFLICT);
        }
        Map<String, String> taskStatuses = lockExpectedTasks(
            command.businessType(), command.businessId(), current.expectedTaskKeys());
        List<String> unfinished = new ArrayList<>();
        for (String taskKey : current.expectedTaskKeys()) {
            String status = taskStatuses.get(taskKey);
            if (!"COMPLETED".equals(status)) {
                unfinished.add(taskKey + "=" + (status == null ? "MISSING" : status));
            }
        }
        if (!unfinished.isEmpty()) {
            throw error(
                ERROR_BARRIER_BLOCKED,
                "业务任务屏障未满足: " + String.join(",", unfinished),
                HttpStatus.HTTP_CONFLICT
            );
        }
        int rows = jdbcTemplate.update("""
            update wms_business_task_barrier
               set status = 'COMPLETED', revision = revision + 1,
                   update_by = ?, update_time = current_timestamp(3)
             where barrier_id = ? and revision = ? and status = 'OPEN'
            """,
            command.actorId(),
            current.barrierId(),
            command.expectedRevision()
        );
        if (rows != 1) {
            throw error(ERROR_BARRIER_CONFLICT, "业务任务屏障完成竞争失败",
                HttpStatus.HTTP_CONFLICT);
        }
        return require(command.businessType(), command.businessId(), command.barrierKey());
    }

    @Override
    public WmsBusinessTaskBarrierSnapshot get(
        String businessType,
        long businessId,
        String barrierKey
    ) {
        requireIdentity(businessType, businessId, barrierKey);
        return require(businessType, businessId, barrierKey);
    }

    private List<String> validateCreate(WmsBusinessTaskBarrierCreateCommand command) {
        if (command == null) {
            throw invalid("业务任务屏障创建命令不能为空");
        }
        requireIdentity(command.businessType(), command.businessId(), command.barrierKey());
        if (command.expectedTaskKeys() == null
            || command.expectedTaskKeys().isEmpty()
            || command.expectedTaskKeys().size() > MAX_TASKS) {
            throw invalid("预期任务键集合必须为1到" + MAX_TASKS + "项");
        }
        List<String> expected = command.expectedTaskKeys().stream()
            .map(this::requireTaskKey)
            .distinct()
            .sorted()
            .toList();
        if (expected.size() != command.expectedTaskKeys().size()) {
            throw invalid("预期任务键集合不得重复");
        }
        return expected;
    }

    private void validateComplete(WmsBusinessTaskBarrierCompleteCommand command) {
        if (command == null) {
            throw invalid("业务任务屏障完成命令不能为空");
        }
        requireIdentity(command.businessType(), command.businessId(), command.barrierKey());
        if (command.expectedRevision() <= 0) {
            throw invalid("业务任务屏障修订号必须大于零");
        }
    }

    private void requireIdentity(String businessType, long businessId, String barrierKey) {
        if (businessType == null || businessType.isBlank() || businessType.length() > 64) {
            throw invalid("业务任务屏障业务类型为空或超长");
        }
        if (businessId <= 0) {
            throw invalid("业务任务屏障业务ID必须大于零");
        }
        if (barrierKey == null || barrierKey.isBlank() || barrierKey.length() > 96) {
            throw invalid("业务任务屏障键为空或超长");
        }
    }

    private String requireTaskKey(String taskKey) {
        if (taskKey == null || taskKey.isBlank() || taskKey.length() > 96) {
            throw invalid("业务任务屏障预期任务键为空或超长");
        }
        return taskKey;
    }

    private WmsBusinessTaskBarrierSnapshot replay(
        WmsBusinessTaskBarrierSnapshot existing,
        List<String> expected
    ) {
        if (!Objects.equals(existing.expectedTaskKeys(), expected)) {
            throw error(
                ERROR_BARRIER_CONFLICT,
                "业务任务屏障稳定键已被不同预期任务集合占用",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return existing;
    }

    private Map<String, String> lockExpectedTasks(
        String businessType,
        long businessId,
        List<String> expectedTaskKeys
    ) {
        String placeholders = String.join(",", java.util.Collections.nCopies(
            expectedTaskKeys.size(), "?"));
        Object[] args = new Object[expectedTaskKeys.size() + 2];
        args[0] = businessType;
        args[1] = businessId;
        for (int index = 0; index < expectedTaskKeys.size(); index++) {
            args[index + 2] = expectedTaskKeys.get(index);
        }
        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
            select task_key, status
              from wms_business_task
             where business_type = ? and business_id = ?
               and task_key in (""" + placeholders + """
            )
             order by task_key
             for update
            """, (row, index) -> {
                result.put(row.getString("task_key"), row.getString("status"));
                return null;
            }, args);
        return result;
    }

    private WmsBusinessTaskBarrierSnapshot findForUpdate(
        String businessType,
        long businessId,
        String barrierKey
    ) {
        List<WmsBusinessTaskBarrierSnapshot> rows = jdbcTemplate.query("""
            select barrier_id, business_type, business_id, barrier_key,
                   expected_task_keys_json, status, revision
              from wms_business_task_barrier
             where business_type = ? and business_id = ? and barrier_key = ?
             for update
            """, (result, row) -> snapshot(result), businessType, businessId, barrierKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WmsBusinessTaskBarrierSnapshot require(
        String businessType,
        long businessId,
        String barrierKey
    ) {
        List<WmsBusinessTaskBarrierSnapshot> rows = jdbcTemplate.query("""
            select barrier_id, business_type, business_id, barrier_key,
                   expected_task_keys_json, status, revision
              from wms_business_task_barrier
             where business_type = ? and business_id = ? and barrier_key = ?
            """, (result, row) -> snapshot(result), businessType, businessId, barrierKey);
        if (rows.isEmpty()) {
            throw error(ERROR_BARRIER_CONFLICT, "业务任务屏障不存在", HttpStatus.HTTP_NOT_FOUND);
        }
        return rows.get(0);
    }

    private WmsBusinessTaskBarrierSnapshot snapshot(java.sql.ResultSet result)
        throws java.sql.SQLException {
        List<String> expected = new ArrayList<>();
        jsonMapper.readTree(result.getString("expected_task_keys_json"))
            .forEach(node -> expected.add(node.asText()));
        return new WmsBusinessTaskBarrierSnapshot(
            result.getLong("barrier_id"),
            result.getString("business_type"),
            result.getLong("business_id"),
            result.getString("barrier_key"),
            expected,
            result.getString("status"),
            result.getInt("revision")
        );
    }

    private ServiceException invalid(String message) {
        return error(ERROR_BARRIER_CONFLICT, message, HttpStatus.HTTP_BAD_REQUEST);
    }

    private ServiceException error(String code, String message, int status) {
        return new ServiceException(code + ": " + message, status);
    }
}
