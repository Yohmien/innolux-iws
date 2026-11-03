/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果代次
 * @Version      : V1.0
 * @Description  : 以锁 revision 作为 generation，CAS推进下发、回执、未知和消费；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectCommand;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectExpectation;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectIdentity;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectResult;
import com.innolux.iws.wms.allocation.effect.service.WmsAllocationEffectService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 分配锁效果推进短事务实现。 */
@Service
public class WmsAllocationEffectServiceImpl implements WmsAllocationEffectService {

    private static final Pattern EFFECT_KEY_PATTERN = Pattern.compile("^WMS:[0-9a-f]{64}$");

    private static final Set<String> TARGETS =
        Set.of("SEND_INTENT", "ACCEPTED", "UNKNOWN", "CONSUMED");

    private static final int TEXT_LIMIT = 500;

    private static final String AUDIT_ALLOCATION_LOCK = "WMS_ALLOCATION_LOCK";

    private static final String AUDIT_CHANNEL_LOCK = "WMS_ACCESS_CHANNEL_LOCK";

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final JsonMapper jsonMapper;

    private final IdentifierGenerator identifierGenerator;

    public WmsAllocationEffectServiceImpl(
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        IdentifierGenerator identifierGenerator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.jsonMapper = jsonMapper;
        this.identifierGenerator = identifierGenerator;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsAllocationEffectResult advance(WmsAllocationEffectCommand command) {
        validate(command);
        List<Long> lockIds = command.expectations().stream()
            .map(WmsAllocationEffectExpectation::allocationLockId)
            .sorted()
            .toList();
        LockedDomain domain = lockDomain(lockIds);
        validateIdentity(command.identity(), domain.allocationLocks());
        if (isReplay(command, domain)) {
            return currentResult(command.targetEffectResult(), domain);
        }
        validateExpectations(command.expectations(), domain.allocationLocks());
        validateTargetState(command.targetEffectResult(), domain);
        validateSelectedLocksOwnChannels(lockIds, domain.channelLocks());

        for (LockRow lock : domain.allocationLocks()) {
            updateAllocationLock(command, lock);
        }
        for (ChannelLockRow channel : domain.channelLocks()) {
            updateChannelLock(command, channel);
        }
        recordEffectAudit(command, domain);
        LockedDomain persisted = loadDomain(lockIds);
        verifyPersisted(command, persisted);
        return currentResult(command.targetEffectResult(), persisted);
    }

    private LockedDomain lockDomain(List<Long> lockIds) {
        InitialDomain initial = loadInitialDomain(lockIds);
        List<Long> channelIds = initial.channelIds().stream().sorted().toList();
        List<Long> storageIds = initial.storageIds().stream().sorted().toList();
        List<Long> channelLockIds = initial.channelLockIds().stream().sorted().toList();
        lockAccessChannels(channelIds);
        lockStorages(storageIds);
        List<LockRow> locks = loadAllocationLocks(lockIds, true);
        List<ChannelLockRow> channels = loadChannelLocks(channelLockIds, true);
        if (locks.size() != lockIds.size()
            || channels.size() != channelLockIds.size()) {
            throw conflict(ERROR_STATE_CONFLICT, "效果推进锁集合在锁前被删除或替换");
        }
        assertInitialDomain(initial, locks, channels);
        return new LockedDomain(locks, channels);
    }

    private LockedDomain loadDomain(List<Long> lockIds) {
        List<LockRow> locks = loadAllocationLocks(lockIds, false);
        List<Long> channelLockIds = locks.stream()
            .map(LockRow::channelLockId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        return new LockedDomain(locks, loadChannelLocks(channelLockIds, false));
    }

    private InitialDomain loadInitialDomain(List<Long> lockIds) {
        List<InitialLockRow> rows = namedJdbc.query("""
            SELECT l.allocation_lock_id, l.channel_lock_id, l.storage_id, c.channel_id
            FROM wms_allocation_lock l
            LEFT JOIN wms_access_channel_lock c
              ON c.channel_lock_id = l.channel_lock_id
            WHERE l.allocation_lock_id IN (:lockIds)
            ORDER BY l.allocation_lock_id
            """, Map.of("lockIds", lockIds), (result, index) -> new InitialLockRow(
            result.getLong("allocation_lock_id"),
            nullableLong(result, "channel_lock_id"),
            result.getLong("storage_id"),
            nullableLong(result, "channel_id")
        ));
        if (rows.size() != lockIds.size()) {
            throw conflict(ERROR_STATE_CONFLICT, "待推进分配锁不存在");
        }
        Map<Long, Long> storageByLock = new LinkedHashMap<>();
        Map<Long, Long> channelLockByLock = new LinkedHashMap<>();
        Map<Long, Long> channelByLock = new LinkedHashMap<>();
        for (InitialLockRow row : rows) {
            storageByLock.put(row.allocationLockId(), row.storageId());
            channelLockByLock.put(row.allocationLockId(), row.channelLockId());
            if (row.channelLockId() != null) {
                if (row.channelId() == null) {
                    throw conflict(ERROR_STATE_CONFLICT, "分配锁关联通道锁缺少通道");
                }
                channelByLock.put(row.channelLockId(), row.channelId());
            }
        }
        return new InitialDomain(
            Set.copyOf(storageByLock.values()),
            Set.copyOf(channelLockByLock.values().stream()
                .filter(Objects::nonNull).toList()),
            Set.copyOf(channelByLock.values()),
            storageByLock,
            channelLockByLock,
            channelByLock
        );
    }

    private void lockAccessChannels(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return;
        }
        List<Long> rows = namedJdbc.queryForList("""
            SELECT channel_id FROM wms_access_channel
            WHERE channel_id IN (:channelIds)
            ORDER BY channel_id
            FOR UPDATE
            """, Map.of("channelIds", channelIds), Long.class);
        if (rows.size() != channelIds.size()) {
            throw conflict(ERROR_STATE_CONFLICT, "分配锁关联访问通道不存在");
        }
    }

    private void lockStorages(List<Long> storageIds) {
        if (storageIds.isEmpty()) {
            return;
        }
        List<Long> rows = namedJdbc.queryForList("""
            SELECT storage_id FROM wms_storage
            WHERE storage_id IN (:storageIds)
            ORDER BY storage_id
            FOR UPDATE
            """, Map.of("storageIds", storageIds), Long.class);
        if (rows.size() != storageIds.size()) {
            throw conflict(ERROR_STATE_CONFLICT, "分配锁关联储位不存在");
        }
    }

    private List<LockRow> loadAllocationLocks(List<Long> lockIds, boolean forUpdate) {
        String sql = """
            SELECT allocation_lock_id, channel_lock_id, business_type, business_id,
                   business_line_id, allocation_unit_id, round_no, task_key,
                   plan_instance_id, storage_id, status, effect_result, evidence_revision,
                   terminal_effect_key, consumption_trace_id, revision
            FROM wms_allocation_lock
            WHERE allocation_lock_id IN (:lockIds)
            ORDER BY allocation_lock_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        return namedJdbc.query(sql, Map.of("lockIds", lockIds), (result, index) -> new LockRow(
            result.getLong("allocation_lock_id"),
            nullableLong(result, "channel_lock_id"),
            result.getString("business_type"),
            result.getLong("business_id"),
            nullableLong(result, "business_line_id"),
            result.getLong("allocation_unit_id"),
            result.getInt("round_no"),
            result.getString("task_key"),
            result.getLong("plan_instance_id"),
            result.getLong("storage_id"),
            result.getString("status"),
            result.getString("effect_result"),
            result.getInt("evidence_revision"),
            result.getString("terminal_effect_key"),
            nullableLong(result, "consumption_trace_id"),
            result.getInt("revision")
        ));
    }

    private List<ChannelLockRow> loadChannelLocks(List<Long> channelLockIds, boolean forUpdate) {
        if (channelLockIds.isEmpty()) {
            return List.of();
        }
        String sql = """
            SELECT channel_lock_id, channel_id, business_type, business_id,
                   business_line_id, allocation_unit_id, round_no, task_key,
                   plan_instance_id, status, effect_result, evidence_revision,
                   terminal_effect_key, consumption_trace_id, revision
            FROM wms_access_channel_lock
            WHERE channel_lock_id IN (:channelLockIds)
            ORDER BY channel_lock_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        return namedJdbc.query(sql, Map.of("channelLockIds", channelLockIds),
            (result, index) -> new ChannelLockRow(
                result.getLong("channel_lock_id"),
                result.getLong("channel_id"),
                result.getString("business_type"),
                result.getLong("business_id"),
                nullableLong(result, "business_line_id"),
                result.getLong("allocation_unit_id"),
                result.getInt("round_no"),
                result.getString("task_key"),
                result.getLong("plan_instance_id"),
                result.getString("status"),
                result.getString("effect_result"),
                result.getInt("evidence_revision"),
                result.getString("terminal_effect_key"),
                nullableLong(result, "consumption_trace_id"),
                result.getInt("revision")
            ));
    }

    private void assertInitialDomain(
        InitialDomain initial,
        List<LockRow> locks,
        List<ChannelLockRow> channels
    ) {
        for (LockRow lock : locks) {
            if (!Objects.equals(
                    initial.storageByLock().get(lock.allocationLockId()),
                    lock.storageId())
                || !Objects.equals(
                    initial.channelLockByLock().get(lock.allocationLockId()),
                    lock.channelLockId())) {
                throw conflict(ERROR_GENERATION_CONFLICT, "分配锁通道或储位在锁前变化");
            }
        }
        for (ChannelLockRow channel : channels) {
            if (!Objects.equals(
                    initial.channelByLock().get(channel.channelLockId()),
                    channel.channelId())) {
                throw conflict(ERROR_GENERATION_CONFLICT, "通道锁映射在锁前变化");
            }
        }
    }

    private void validateIdentity(
        WmsAllocationEffectIdentity identity,
        List<LockRow> locks
    ) {
        for (LockRow lock : locks) {
            if (!Objects.equals(identity.businessType(), lock.businessType())
                || identity.businessId() != lock.businessId()
                || !Objects.equals(identity.businessLineId(), lock.businessLineId())
                || identity.allocationUnitId() != lock.allocationUnitId()
                || identity.roundNo() != lock.roundNo()
                || !Objects.equals(identity.taskKey(), lock.taskKey())
                || identity.planInstanceId() != lock.planInstanceId()) {
                throw conflict(ERROR_EFFECT_CONFLICT, "效果推进与原分配锁任务身份不一致");
            }
        }
    }

    private void validateExpectations(
        List<WmsAllocationEffectExpectation> expectations,
        List<LockRow> locks
    ) {
        Map<Long, LockRow> byId = locks.stream().collect(Collectors.toMap(
            LockRow::allocationLockId, Function.identity()));
        if (expectations.size() != locks.size()) {
            throw conflict(ERROR_GENERATION_CONFLICT, "效果推进期望锁集合不完整");
        }
        for (WmsAllocationEffectExpectation expectation : expectations) {
            LockRow lock = byId.get(expectation.allocationLockId());
            if (lock == null
                || lock.revision() != expectation.expectedGeneration()
                || !Objects.equals(lock.effectResult(), expectation.expectedEffectResult())) {
                throw conflict(ERROR_GENERATION_CONFLICT,
                    "分配锁 generation 或效果已变化: " + expectation.allocationLockId());
            }
        }
    }

    private void validateTargetState(String target, LockedDomain domain) {
        if (domain.allocationLocks().stream().anyMatch(lock ->
            !"LOCKED".equals(lock.status()))) {
            throw conflict(ERROR_STATE_CONFLICT, "只有活动分配锁可推进外部效果");
        }
        Set<String> effects = domain.allocationLocks().stream()
            .map(LockRow::effectResult)
            .collect(Collectors.toSet());
        if (effects.size() != 1 || domain.channelLocks().size() != 1) {
            throw conflict(ERROR_STATE_CONFLICT, "同一效果推进必须对应单一通道和单一当前状态");
        }
        String current = effects.iterator().next();
        boolean allowed = switch (target) {
            case "SEND_INTENT" -> "NONE".equals(current);
            case "ACCEPTED", "UNKNOWN" -> "SEND_INTENT".equals(current);
            case "CONSUMED" -> Set.of("ACCEPTED", "UNKNOWN").contains(current);
            default -> false;
        };
        ChannelLockRow channel = domain.channelLocks().getFirst();
        if (!allowed
            || !"LOCKED".equals(channel.status())
            || !Objects.equals(current, channel.effectResult())) {
            throw conflict(ERROR_STATE_CONFLICT,
                "不允许从 " + current + " 推进到 " + target);
        }
    }

    private void validateSelectedLocksOwnChannels(
        List<Long> selectedLockIds,
        List<ChannelLockRow> channels
    ) {
        for (ChannelLockRow channel : channels) {
            List<Long> allLockIds = jdbcTemplate.queryForList("""
                SELECT allocation_lock_id FROM wms_allocation_lock
                WHERE channel_lock_id = ?
                ORDER BY allocation_lock_id
                """, Long.class, channel.channelLockId());
            if (!allLockIds.equals(selectedLockIds)) {
                throw conflict(ERROR_STATE_CONFLICT,
                    "效果推进必须覆盖通道下全部分配锁: " + channel.channelLockId());
            }
        }
    }

    private void updateAllocationLock(
        WmsAllocationEffectCommand command,
        LockRow lock
    ) {
        String status = "CONSUMED".equals(command.targetEffectResult())
            ? "CONSUMED" : "LOCKED";
        Object terminalAt = "CONSUMED".equals(command.targetEffectResult())
            ? command.occurredAt() : null;
        int rows = jdbcTemplate.update("""
            UPDATE wms_allocation_lock
            SET status = ?, effect_result = ?,
                evidence_revision = evidence_revision + 1,
                terminal_effect_key = ?, consumption_trace_id = ?,
                terminal_release_id = NULL, terminal_at = ?,
                revision = revision + 1, update_by = ?, update_time = ?
            WHERE allocation_lock_id = ? AND status = 'LOCKED'
              AND effect_result = ? AND revision = ?
            """,
            status,
            command.targetEffectResult(),
            command.terminalEffectKey(),
            command.consumptionTraceId(),
            terminalAt,
            command.actorId(),
            command.occurredAt(),
            lock.allocationLockId(),
            lock.effectResult(),
            lock.revision()
        );
        if (rows != 1) {
            throw conflict(ERROR_GENERATION_CONFLICT,
                "推进分配锁 generation 失败: " + lock.allocationLockId());
        }
    }

    private void updateChannelLock(
        WmsAllocationEffectCommand command,
        ChannelLockRow channel
    ) {
        String status = "CONSUMED".equals(command.targetEffectResult())
            ? "CONSUMED" : "LOCKED";
        Object terminalAt = "CONSUMED".equals(command.targetEffectResult())
            ? command.occurredAt() : null;
        int rows = jdbcTemplate.update("""
            UPDATE wms_access_channel_lock
            SET status = ?, effect_result = ?,
                evidence_revision = evidence_revision + 1,
                terminal_effect_key = ?, consumption_trace_id = ?,
                terminal_release_id = NULL, terminal_at = ?,
                revision = revision + 1, update_by = ?, update_time = ?
            WHERE channel_lock_id = ? AND status = 'LOCKED'
              AND effect_result = ? AND revision = ?
            """,
            status,
            command.targetEffectResult(),
            command.terminalEffectKey(),
            command.consumptionTraceId(),
            terminalAt,
            command.actorId(),
            command.occurredAt(),
            channel.channelLockId(),
            channel.effectResult(),
            channel.revision()
        );
        if (rows != 1) {
            throw conflict(ERROR_GENERATION_CONFLICT,
                "推进通道锁 generation 失败: " + channel.channelLockId());
        }
    }

    private void recordEffectAudit(
        WmsAllocationEffectCommand command,
        LockedDomain domain
    ) {
        String action = "ALLOCATION_EFFECT_" + command.targetEffectResult();
        String result = "UNKNOWN".equals(command.targetEffectResult())
            ? "UNKNOWN" : "SUCCESS";
        for (LockRow lock : domain.allocationLocks()) {
            insertEffectAudit(
                command,
                action,
                result,
                AUDIT_ALLOCATION_LOCK,
                lock.allocationLockId(),
                lock.status(),
                lock.effectResult(),
                lock.revision(),
                "CONSUMED".equals(command.targetEffectResult()) ? "CONSUMED" : "LOCKED",
                command.targetEffectResult(),
                lock.revision() + 1
            );
        }
        for (ChannelLockRow channel : domain.channelLocks()) {
            insertEffectAudit(
                command,
                action,
                result,
                AUDIT_CHANNEL_LOCK,
                channel.channelLockId(),
                channel.status(),
                channel.effectResult(),
                channel.revision(),
                "CONSUMED".equals(command.targetEffectResult()) ? "CONSUMED" : "LOCKED",
                command.targetEffectResult(),
                channel.revision() + 1
            );
        }
    }

    private void insertEffectAudit(
        WmsAllocationEffectCommand command,
        String action,
        String result,
        String objectType,
        long objectId,
        String beforeStatus,
        String beforeEffect,
        int beforeRevision,
        String afterStatus,
        String afterEffect,
        int afterRevision
    ) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", beforeStatus);
        before.put("effectResult", beforeEffect);
        before.put("revision", beforeRevision);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", afterStatus);
        after.put("effectResult", afterEffect);
        after.put("revision", afterRevision);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("targetEffectResult", command.targetEffectResult());
        evidence.put("evidenceRef", command.evidenceRef());
        evidence.put("terminalEffectKey", command.terminalEffectKey());
        evidence.put("consumptionTraceId", command.consumptionTraceId());
        evidence.put("businessType", command.identity().businessType());
        evidence.put("businessId", command.identity().businessId());
        evidence.put("businessLineId", command.identity().businessLineId());
        evidence.put("allocationUnitId", command.identity().allocationUnitId());
        evidence.put("taskKey", command.identity().taskKey());
        evidence.put("planInstanceId", command.identity().planInstanceId());
        jdbcTemplate.update("""
            INSERT INTO wms_object_audit (
              audit_id, object_type, object_id, action, command_key,
              before_summary, after_summary, actor_user_id, initiator_type,
              initiator_id, dept_id, result, evidence_json, occurred_at, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?, current_timestamp(3))
            """,
            identifierGenerator.nextId(null).longValue(),
            objectType,
            objectId,
            action,
            "ALLOC-EFFECT:" + command.targetEffectResult() + ":" + objectId
                + ":" + afterRevision,
            jsonMapper.writeValueAsString(before),
            jsonMapper.writeValueAsString(after),
            command.actorId(),
            String.valueOf(command.actorId()),
            command.departmentId(),
            result,
            jsonMapper.writeValueAsString(evidence),
            command.occurredAt()
        );
    }

    private boolean isReplay(
        WmsAllocationEffectCommand command,
        LockedDomain domain
    ) {
        if ("CONSUMED".equals(command.targetEffectResult())) {
            return domain.allocationLocks().stream().allMatch(lock ->
                "CONSUMED".equals(lock.status())
                    && "CONSUMED".equals(lock.effectResult())
                    && Objects.equals(command.terminalEffectKey(), lock.terminalEffectKey())
                    && Objects.equals(command.consumptionTraceId(), lock.consumptionTraceId()))
                && domain.channelLocks().size() == 1
                && domain.channelLocks().stream().allMatch(channel ->
                    "CONSUMED".equals(channel.status())
                        && "CONSUMED".equals(channel.effectResult())
                        && Objects.equals(command.terminalEffectKey(), channel.terminalEffectKey())
                        && Objects.equals(command.consumptionTraceId(), channel.consumptionTraceId()));
        }
        return domain.allocationLocks().stream().allMatch(lock ->
            "LOCKED".equals(lock.status())
                && command.targetEffectResult().equals(lock.effectResult()))
            && domain.channelLocks().size() == 1
            && domain.channelLocks().stream().allMatch(channel ->
                "LOCKED".equals(channel.status())
                    && command.targetEffectResult().equals(channel.effectResult()));
    }

    private void verifyPersisted(
        WmsAllocationEffectCommand command,
        LockedDomain domain
    ) {
        if (!isReplay(command, domain)) {
            throw conflict(ERROR_STATE_CONFLICT, "效果推进终态回读不一致");
        }
    }

    private WmsAllocationEffectResult currentResult(
        String target,
        LockedDomain domain
    ) {
        List<Long> lockIds = domain.allocationLocks().stream()
            .map(LockRow::allocationLockId)
            .toList();
        List<Integer> generations = domain.allocationLocks().stream()
            .map(LockRow::revision)
            .toList();
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", target);
        checkpoint.put("allocationLockIds", lockIds);
        checkpoint.put("generations", generations);
        checkpoint.put("channelLockIds", domain.channelLocks().stream()
            .map(ChannelLockRow::channelLockId).toList());
        checkpoint.put("terminalEffectKey", domain.allocationLocks().stream()
            .map(LockRow::terminalEffectKey).filter(Objects::nonNull).findFirst().orElse(null));
        checkpoint.put("consumptionTraceId", domain.allocationLocks().stream()
            .map(LockRow::consumptionTraceId).filter(Objects::nonNull).findFirst().orElse(null));
        return new WmsAllocationEffectResult(
            target,
            lockIds,
            generations,
            jsonMapper.writeValueAsString(checkpoint)
        );
    }

    private void validate(WmsAllocationEffectCommand command) {
        if (command == null
            || command.identity() == null
            || command.expectations() == null
            || command.expectations().isEmpty()
            || command.occurredAt() == null
            || command.actorId() == null
            || command.actorId() <= 0) {
            throw invalid("效果推进命令、身份、锁期望、发生时间或操作者不能为空");
        }
        if (!TARGETS.contains(command.targetEffectResult())) {
            throw invalid("不支持的效果目标: " + command.targetEffectResult());
        }
        requireText(command.evidenceRef(), "evidenceRef", TEXT_LIMIT);
        if ("CONSUMED".equals(command.targetEffectResult())) {
            if (command.terminalEffectKey() == null
                || !EFFECT_KEY_PATTERN.matcher(command.terminalEffectKey()).matches()
                || command.consumptionTraceId() == null
                || command.consumptionTraceId() <= 0) {
                throw invalid("消费终态必须提供效果键和正数traceId");
            }
        } else if (command.terminalEffectKey() != null
            || command.consumptionTraceId() != null) {
            throw invalid("非消费效果不得提供终态效果键或traceId");
        }
        Set<Long> ids = new HashSet<>();
        for (WmsAllocationEffectExpectation expectation : command.expectations()) {
            if (expectation == null
                || expectation.allocationLockId() <= 0
                || expectation.expectedGeneration() < 1
                || expectation.expectedEffectResult() == null
                || !ids.add(expectation.allocationLockId())) {
                throw invalid("效果推进锁期望非法");
            }
        }
        validateIdentity(command.identity());
        if (command.departmentId() != null && command.departmentId() <= 0) {
            throw invalid("departmentId必须为正数或null");
        }
    }

    private void validateIdentity(WmsAllocationEffectIdentity identity) {
        requireText(identity.businessType(), "businessType", 64);
        requireText(identity.businessName(), "businessName", 128);
        if (identity.businessId() <= 0
            || identity.allocationUnitId() <= 0
            || identity.roundNo() < 1
            || identity.planInstanceId() <= 0) {
            throw invalid("效果推进业务ID、分配单元、轮次或实例非法");
        }
        if (identity.businessLineId() != null && identity.businessLineId() <= 0) {
            throw invalid("businessLineId必须为正数或null");
        }
        if (identity.businessNo() != null && identity.businessNo().length() > 100) {
            throw invalid("businessNo长度不能超过100");
        }
        requireText(identity.taskKey(), "taskKey", 96);
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + "不能为空且长度不能超过" + maxLength);
        }
    }

    private static Long nullableLong(java.sql.ResultSet result, String column)
        throws java.sql.SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private ServiceException invalid(String message) {
        return error(ERROR_INPUT_INVALID, message, HttpStatus.HTTP_BAD_REQUEST);
    }

    private ServiceException conflict(String code, String message) {
        return error(code, message, HttpStatus.HTTP_CONFLICT);
    }

    private ServiceException error(String code, String message, int status) {
        return new ServiceException(code + ": " + message, status);
    }

    private record InitialLockRow(
        long allocationLockId,
        Long channelLockId,
        long storageId,
        Long channelId
    ) {
    }

    private record InitialDomain(
        Set<Long> storageIds,
        Set<Long> channelLockIds,
        Set<Long> channelIds,
        Map<Long, Long> storageByLock,
        Map<Long, Long> channelLockByLock,
        Map<Long, Long> channelByLock
    ) {
    }

    private record LockedDomain(
        List<LockRow> allocationLocks,
        List<ChannelLockRow> channelLocks
    ) {
    }

    private record LockRow(
        long allocationLockId,
        Long channelLockId,
        String businessType,
        long businessId,
        Long businessLineId,
        long allocationUnitId,
        int roundNo,
        String taskKey,
        long planInstanceId,
        long storageId,
        String status,
        String effectResult,
        int evidenceRevision,
        String terminalEffectKey,
        Long consumptionTraceId,
        int revision
    ) {
    }

    private record ChannelLockRow(
        long channelLockId,
        long channelId,
        String businessType,
        long businessId,
        Long businessLineId,
        long allocationUnitId,
        int roundNo,
        String taskKey,
        long planInstanceId,
        String status,
        String effectResult,
        int evidenceRevision,
        String terminalEffectKey,
        Long consumptionTraceId,
        int revision
    ) {
    }
}
