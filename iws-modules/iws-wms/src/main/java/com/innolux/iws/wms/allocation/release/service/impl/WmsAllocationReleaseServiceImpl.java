/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 锁内重验无效果或对账后无效果证据，只释放本任务分配锁并在收敛后终结通道锁；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationEvidenceResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCheckCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseCommand;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseIdentity;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseLockExpectation;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseResult;
import com.innolux.iws.wms.allocation.release.domain.WmsAllocationReleaseVerifyCommand;
import com.innolux.iws.wms.allocation.release.service.WmsAllocationReleaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/**
 * 分配释放短事务实现。
 *
 * <p>先以只读查询确定通道、储位与锁 ID，再按通道、储位、分配锁、通道锁顺序加锁。
 * 释放命令必须携带 CHECK 阶段持久化的规范化证据摘要、证据修订号和逐锁期望值；
 * 锁后 current read 重算摘要，任一变化均拒绝释放。</p>
 */
@Service
public class WmsAllocationReleaseServiceImpl implements WmsAllocationReleaseService {

    private static final Pattern EFFECT_KEY_PATTERN = Pattern.compile("^WMS:[0-9a-f]{64}$");

    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private static final String EVIDENCE_NO_EFFECT = "NO_EFFECT";

    private static final String EVIDENCE_RECONCILED_NO_EFFECT =
        "RECONCILED_NO_EFFECT";

    private static final Set<String> EVIDENCE_TYPES =
        Set.of(EVIDENCE_NO_EFFECT, EVIDENCE_RECONCILED_NO_EFFECT);

    private static final Set<String> RELEASABLE_EFFECT_RESULTS =
        Set.of("NONE", "NO_EFFECT");

    private static final Set<String> RECONCILABLE_EFFECT_RESULTS =
        Set.of("SEND_INTENT", "ACCEPTED", "UNKNOWN");

    private static final int TEXT_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    private final IdentifierGenerator identifierGenerator;

    private final JsonMapper jsonMapper;

    public WmsAllocationReleaseServiceImpl(
        JdbcTemplate jdbcTemplate,
        IdentifierGenerator identifierGenerator,
        JsonMapper jsonMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.identifierGenerator = identifierGenerator;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsAllocationEvidenceResult checkEvidence(WmsAllocationReleaseCheckCommand command) {
        validateCheck(command);
        LockedDomain domain = lockDomain(command.allocationLockIds());
        requireAllLocked(domain.allocationLocks());
        validateIdentity(command.identity(), domain.allocationLocks(), domain.channelLocks());
        validateReleasable(
            domain.allocationLocks(), domain.channelLocks(), command.evidenceType());

        int evidenceRevision = domain.allocationLocks().stream()
            .mapToInt(LockRow::revision)
            .max()
            .orElseThrow();
        String evidenceHash = evidenceHash(
            command.identity(), command.evidenceType(), command.evidenceRef(), domain);
        List<WmsAllocationReleaseLockExpectation> expectations =
            domain.allocationLocks().stream()
                .map(row -> new WmsAllocationReleaseLockExpectation(
                    row.allocationLockId(), row.revision(), row.effectResult()))
                .toList();
        List<Long> channelLockIds = domain.channelLocks().stream()
            .map(ChannelLockRow::channelLockId)
            .toList();
        return new WmsAllocationEvidenceResult(
            evidenceHash,
            evidenceRevision,
            channelLockIds,
            expectations,
            jsonMapper.writeValueAsString(Map.of(
                "status", "EVIDENCE_CHECKED",
                "evidenceHash", evidenceHash,
                "evidenceRevision", evidenceRevision,
                "allocationLockIds", domain.allocationLocks().stream()
                    .map(LockRow::allocationLockId).toList(),
                "channelLockIds", channelLockIds,
                "expectations", expectations
            ))
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WmsAllocationReleaseResult release(WmsAllocationReleaseCommand command) {
        validateRelease(command);
        List<Long> lockIds = command.expectations().stream()
            .map(WmsAllocationReleaseLockExpectation::allocationLockId)
            .toList();
        LockedDomain domain = lockDomain(lockIds);

        if (domain.allocationLocks().stream().allMatch(row -> "RELEASED".equals(row.status()))) {
            return replay(command, domain);
        }
        requireAllLocked(domain.allocationLocks());
        validateIdentity(command.identity(), domain.allocationLocks(), domain.channelLocks());
        validateReleasable(
            domain.allocationLocks(),
            domain.channelLocks(),
            command.normalizedEvidenceType()
        );
        validateExpectations(command.expectations(), domain.allocationLocks());

        int evidenceRevision = domain.allocationLocks().stream()
            .mapToInt(LockRow::revision)
            .max()
            .orElseThrow();
        String currentEvidenceHash = evidenceHash(
            command.identity(),
            command.normalizedEvidenceType(),
            command.evidenceRef(),
            domain
        );
        if (evidenceRevision != command.evidenceRevision()
            || !currentEvidenceHash.equals(command.evidenceHash())) {
            throw conflict(ERROR_EVIDENCE_CHANGED,
                "释放检查后锁修订或证据摘要已变化");
        }
        rejectTerminalEffectConflict(command.effectKey(), domain.channelLocks());

        List<Long> releaseIds = new ArrayList<>();
        Map<Long, Long> lastReleaseByChannel = new LinkedHashMap<>();
        for (LockRow lock : domain.allocationLocks()) {
            String releaseKey = releaseEffectKey(command.effectKey(), lock.allocationLockId());
            long releaseId = nextId();
            insertRelease(command, lock, releaseId, releaseKey, evidenceRevision);
            updateAllocationLock(command, lock, releaseId, releaseKey, evidenceRevision);
            releaseIds.add(releaseId);
            if (lock.channelLockId() != null) {
                lastReleaseByChannel.merge(
                    lock.channelLockId(), releaseId, Math::max);
            }
        }

        List<Long> releasedChannelLockIds = new ArrayList<>();
        for (ChannelLockRow channel : domain.channelLocks()) {
            Long remaining = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_allocation_lock
                WHERE channel_lock_id = ? AND status = 'LOCKED'
                """, Long.class, channel.channelLockId());
            if (remaining != null && remaining == 0L) {
                long terminalReleaseId = lastReleaseByChannel.get(channel.channelLockId());
                updateChannelLock(
                    command, channel, terminalReleaseId, evidenceRevision);
                releasedChannelLockIds.add(channel.channelLockId());
            }
        }

        WmsAllocationReleaseResult result = result(
            command.effectKey(),
            command.evidenceHash(),
            evidenceRevision,
            domain.allocationLocks().stream().map(LockRow::allocationLockId).toList(),
            releaseIds,
            releasedChannelLockIds
        );
        verifyPersisted(result);
        return result;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public WmsAllocationReleaseResult verifyRelease(WmsAllocationReleaseVerifyCommand command) {
        validateVerify(command);
        List<Long> lockIds = command.allocationLockIds().stream().sorted().toList();
        List<ReleaseRow> releases = loadReleasesByDerivedKeys(
            command.effectKey(), lockIds, false);
        if (releases.size() != lockIds.size()) {
            throw error(ERROR_VERIFY_FAILED,
                "释放流水数量与分配锁不一致", HttpStatus.HTTP_CONFLICT);
        }
        List<LockRow> locks = loadAllocationLocks(lockIds, false);
        if (locks.size() != lockIds.size()
            || locks.stream().anyMatch(lock ->
                !"RELEASED".equals(lock.status())
                    || !EVIDENCE_NO_EFFECT.equals(lock.effectResult()))) {
            throw error(ERROR_VERIFY_FAILED,
                "分配锁未达到NO_EFFECT释放终态", HttpStatus.HTTP_CONFLICT);
        }
        validateVerifyIdentity(command.identity(), locks);
        validateReleaseRows(command.identity(), releases);
        Map<Long, ReleaseRow> releaseById = releases.stream().collect(Collectors.toMap(
            ReleaseRow::allocationLockId, Function.identity()));
        for (LockRow lock : locks) {
            ReleaseRow release = releaseById.get(lock.allocationLockId());
            if (release == null
                || lock.terminalReleaseId() == null
                || lock.terminalReleaseId() != release.releaseId()
                || !Objects.equals(lock.terminalEffectKey(), release.effectKey())) {
                throw error(ERROR_VERIFY_FAILED,
                    "释放流水与分配锁终态不一致: " + lock.allocationLockId(),
                    HttpStatus.HTTP_CONFLICT);
            }
        }
        List<Long> channelLockIds = locks.stream()
            .map(LockRow::channelLockId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        List<Long> releasedChannels = loadChannelLocks(channelLockIds, false).stream()
            .filter(channel -> "RELEASED".equals(channel.status()))
            .filter(channel -> Objects.equals(
                channel.terminalEffectKey(), command.effectKey()))
            .map(ChannelLockRow::channelLockId)
            .toList();
        int evidenceRevision = releases.stream()
            .mapToInt(ReleaseRow::evidenceRevision)
            .max()
            .orElse(0);
        return result(
            command.effectKey(), command.evidenceHash(), evidenceRevision,
            lockIds, releases.stream().map(ReleaseRow::releaseId).toList(),
            releasedChannels
        );
    }

    private LockedDomain lockDomain(List<Long> lockIds) {
        InitialDomain initial = loadInitialDomain(lockIds);
        List<Long> channelIds = initial.channelByLock().values().stream()
            .distinct()
            .sorted()
            .toList();
        List<Long> storageIds = initial.storageByLock().values().stream()
            .distinct()
            .sorted()
            .toList();
        List<Long> channelLockIds = initial.channelLockByLock().values().stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        lockAccessChannels(channelIds);
        lockStorages(storageIds);
        List<LockRow> allocationLocks = loadAllocationLocks(lockIds, true);
        if (allocationLocks.size() != lockIds.size()) {
            throw conflict(ERROR_STATE_CONFLICT, "待释放分配锁在锁前被删除或替换");
        }
        List<ChannelLockRow> channelLocks = loadChannelLocks(channelLockIds, true);
        if (channelLocks.size() != channelLockIds.size()) {
            throw conflict(ERROR_STATE_CONFLICT, "关联通道锁在锁前被删除或替换");
        }
        assertInitialDomain(initial, allocationLocks, channelLocks);
        return new LockedDomain(allocationLocks, channelLocks);
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
            throw error(ERROR_STATE_CONFLICT,
                "待释放分配锁不存在", HttpStatus.HTTP_NOT_FOUND);
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
        return new InitialDomain(storageByLock, channelLockByLock, channelByLock);
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
            SELECT allocation_lock_id, channel_lock_id, business_type, business_name,
                   business_id, business_no, business_line_id, allocation_unit_id,
                   round_no, task_key, plan_instance_id, storage_id, status,
                   effect_result, effect_key, evidence_revision, terminal_effect_key,
                   terminal_release_id, revision
            FROM wms_allocation_lock
            WHERE allocation_lock_id IN (:lockIds)
            ORDER BY allocation_lock_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        return namedJdbc.query(sql, Map.of("lockIds", lockIds), (result, index) -> new LockRow(
            result.getLong("allocation_lock_id"),
            nullableLong(result, "channel_lock_id"),
            result.getString("business_type"),
            result.getString("business_name"),
            result.getLong("business_id"),
            result.getString("business_no"),
            nullableLong(result, "business_line_id"),
            result.getLong("allocation_unit_id"),
            result.getInt("round_no"),
            result.getString("task_key"),
            result.getLong("plan_instance_id"),
            result.getLong("storage_id"),
            result.getString("status"),
            result.getString("effect_result"),
            result.getString("effect_key"),
            result.getInt("evidence_revision"),
            result.getString("terminal_effect_key"),
            nullableLong(result, "terminal_release_id"),
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
                   plan_instance_id, status, effect_result, lock_effect_key,
                   terminal_effect_key, evidence_revision, revision
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
                result.getString("lock_effect_key"),
                result.getString("terminal_effect_key"),
                result.getInt("evidence_revision"),
                result.getInt("revision")
            ));
    }

    private void assertInitialDomain(
        InitialDomain initial,
        List<LockRow> current,
        List<ChannelLockRow> channels
    ) {
        Map<Long, ChannelLockRow> channelById = channels.stream().collect(Collectors.toMap(
            ChannelLockRow::channelLockId, Function.identity()));
        for (LockRow lock : current) {
            if (!Objects.equals(
                    initial.storageByLock().get(lock.allocationLockId()), lock.storageId())
                || !Objects.equals(
                    initial.channelLockByLock().get(lock.allocationLockId()),
                    lock.channelLockId())) {
                throw conflict(ERROR_STATE_CONFLICT, "分配锁储位或通道在锁前变化");
            }
            if (lock.channelLockId() != null) {
                ChannelLockRow channel = channelById.get(lock.channelLockId());
                if (channel == null
                    || !Objects.equals(
                        initial.channelByLock().get(lock.channelLockId()),
                        channel.channelId())) {
                    throw conflict(ERROR_STATE_CONFLICT, "通道锁通道映射在锁前变化");
                }
            }
        }
    }

    private void requireAllLocked(List<LockRow> locks) {
        if (locks.stream().anyMatch(lock -> "CONSUMED".equals(lock.status()))) {
            throw conflict(ERROR_ALREADY_CONSUMED, "分配锁已被真实业务消费，不能释放");
        }
        if (locks.stream().anyMatch(lock -> !"LOCKED".equals(lock.status()))) {
            throw conflict(ERROR_STATE_CONFLICT, "待释放分配锁不是统一活动状态");
        }
    }

    private void validateReleasable(
        List<LockRow> locks,
        List<ChannelLockRow> channels,
        String evidenceType
    ) {
        Set<String> allowedEffects = EVIDENCE_RECONCILED_NO_EFFECT.equals(evidenceType)
            ? RECONCILABLE_EFFECT_RESULTS : RELEASABLE_EFFECT_RESULTS;
        if (locks.stream().anyMatch(lock ->
            !allowedEffects.contains(lock.effectResult()))) {
            throw conflict(ERROR_EVIDENCE_INVALID,
                "分配锁外部效果与" + evidenceType + "证据不匹配");
        }
        Map<Long, ChannelLockRow> channelById = channels.stream().collect(Collectors.toMap(
            ChannelLockRow::channelLockId, Function.identity()));
        for (LockRow lock : locks) {
            if (lock.channelLockId() == null) {
                continue;
            }
            ChannelLockRow channel = channelById.get(lock.channelLockId());
            if (channel == null
                || !Objects.equals(lock.effectResult(), channel.effectResult())) {
                throw conflict(ERROR_EVIDENCE_INVALID,
                    "分配锁与通道锁外部效果不一致: " + lock.allocationLockId());
            }
        }
        for (ChannelLockRow channel : channels) {
            if (!"LOCKED".equals(channel.status())) {
                throw conflict(ERROR_STATE_CONFLICT, "关联通道锁不是活动状态");
            }
            if (!allowedEffects.contains(channel.effectResult())) {
                throw conflict(ERROR_EVIDENCE_INVALID,
                    "通道锁效果与" + evidenceType + "证据不匹配: "
                        + channel.channelLockId());
            }
        }
    }

    private void validateIdentity(
        WmsAllocationReleaseIdentity identity,
        List<LockRow> locks,
        List<ChannelLockRow> channels
    ) {
        for (LockRow lock : locks) {
            if (!Objects.equals(identity.businessType(), lock.businessType())
                || identity.businessId() != lock.businessId()
                || !Objects.equals(identity.businessLineId(), lock.businessLineId())
                || identity.allocationUnitId() != lock.allocationUnitId()
                || identity.roundNo() != lock.roundNo()
                || !Objects.equals(identity.lockTaskKey(), lock.taskKey())) {
                throw conflict(ERROR_EFFECT_CONFLICT,
                    "释放命令与分配锁持有人身份不一致: " + lock.allocationLockId());
            }
        }
        for (ChannelLockRow channel : channels) {
            if (!Objects.equals(identity.businessType(), channel.businessType())
                || identity.businessId() != channel.businessId()
                || !Objects.equals(identity.businessLineId(), channel.businessLineId())
                || identity.allocationUnitId() != channel.allocationUnitId()
                || identity.roundNo() != channel.roundNo()
                || !Objects.equals(identity.lockTaskKey(), channel.taskKey())) {
                throw conflict(ERROR_EFFECT_CONFLICT,
                    "释放命令与通道锁持有人身份不一致: " + channel.channelLockId());
            }
        }
    }

    private void validateExpectations(
        List<WmsAllocationReleaseLockExpectation> expected,
        List<LockRow> current
    ) {
        Map<Long, LockRow> currentById = current.stream().collect(Collectors.toMap(
            LockRow::allocationLockId, Function.identity()));
        if (expected.size() != current.size()) {
            throw conflict(ERROR_EVIDENCE_CHANGED, "待释放分配锁集合与检查结果不一致");
        }
        for (WmsAllocationReleaseLockExpectation item : expected) {
            LockRow lock = currentById.get(item.allocationLockId());
            if (lock == null
                || lock.revision() != item.revision()
                || !Objects.equals(lock.effectResult(), item.effectResult())) {
                throw conflict(ERROR_EVIDENCE_CHANGED,
                    "分配锁检查后发生变化: " + item.allocationLockId());
            }
        }
    }

    private void rejectTerminalEffectConflict(
        String terminalEffectKey,
        List<ChannelLockRow> targetChannels
    ) {
        Set<Long> targetIds = targetChannels.stream()
            .map(ChannelLockRow::channelLockId)
            .collect(Collectors.toSet());
        List<Long> owners = jdbcTemplate.queryForList("""
            SELECT channel_lock_id FROM wms_access_channel_lock
            WHERE terminal_effect_key = ?
            """, Long.class, terminalEffectKey);
        if (owners.stream().anyMatch(owner -> !targetIds.contains(owner))) {
            throw conflict(ERROR_EFFECT_CONFLICT, "释放效果键已被其他通道锁终结使用");
        }
    }

    private void insertRelease(
        WmsAllocationReleaseCommand command,
        LockRow lock,
        long releaseId,
        String releaseKey,
        int evidenceRevision
    ) {
        jdbcTemplate.update("""
            INSERT INTO wms_allocation_release (
              release_id, allocation_lock_id, channel_lock_id, business_type,
              business_name, business_id, business_no, business_line_id,
              allocation_unit_id, round_no, phase, task_key, plan_instance_id,
              evidence_revision, evidence_ref, effect_key, before_status,
              after_status, release_reason, operator_id, occurred_at,
              create_dept, create_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RELEASE', ?, ?, ?, ?, ?,
              'LOCKED', 'RELEASED', ?, ?, ?, ?, ?)
            """,
            releaseId,
            lock.allocationLockId(),
            lock.channelLockId(),
            lock.businessType(),
            lock.businessName(),
            lock.businessId(),
            lock.businessNo(),
            lock.businessLineId(),
            lock.allocationUnitId(),
            lock.roundNo(),
            command.identity().taskKey(),
            command.identity().planInstanceId(),
            evidenceRevision,
            command.evidenceRef(),
            releaseKey,
            command.reason(),
            command.actorId(),
            command.occurredAt(),
            command.departmentId(),
            command.actorId()
        );
    }

    private void updateAllocationLock(
        WmsAllocationReleaseCommand command,
        LockRow lock,
        long releaseId,
        String releaseKey,
        int evidenceRevision
    ) {
        int rows = jdbcTemplate.update("""
            UPDATE wms_allocation_lock
            SET status = 'RELEASED', effect_result = 'NO_EFFECT',
                evidence_revision = ?, terminal_effect_key = ?,
                terminal_release_id = ?, terminal_at = ?,
                revision = revision + 1, update_by = ?, update_time = ?
            WHERE allocation_lock_id = ? AND status = 'LOCKED' AND revision = ?
            """,
            evidenceRevision,
            releaseKey,
            releaseId,
            command.occurredAt(),
            command.actorId(),
            command.occurredAt(),
            lock.allocationLockId(),
            lock.revision()
        );
        if (rows != 1) {
            throw conflict(ERROR_EVIDENCE_CHANGED,
                "释放分配锁时修订冲突: " + lock.allocationLockId());
        }
    }

    private void updateChannelLock(
        WmsAllocationReleaseCommand command,
        ChannelLockRow channel,
        long terminalReleaseId,
        int evidenceRevision
    ) {
        int rows = jdbcTemplate.update("""
            UPDATE wms_access_channel_lock
            SET status = 'RELEASED', effect_result = 'NO_EFFECT',
                evidence_revision = ?, terminal_effect_key = ?,
                terminal_release_id = ?, terminal_at = ?,
                revision = revision + 1, update_by = ?, update_time = ?
            WHERE channel_lock_id = ? AND status = 'LOCKED' AND revision = ?
            """,
            evidenceRevision,
            command.effectKey(),
            terminalReleaseId,
            command.occurredAt(),
            command.actorId(),
            command.occurredAt(),
            channel.channelLockId(),
            channel.revision()
        );
        if (rows != 1) {
            throw conflict(ERROR_EVIDENCE_CHANGED,
                "终结通道锁时修订冲突: " + channel.channelLockId());
        }
    }

    private WmsAllocationReleaseResult replay(
        WmsAllocationReleaseCommand command,
        LockedDomain domain
    ) {
        validateIdentity(command.identity(), domain.allocationLocks(), domain.channelLocks());
        List<Long> lockIds = domain.allocationLocks().stream()
            .map(LockRow::allocationLockId)
            .toList();
        List<ReleaseRow> releases = loadReleasesByDerivedKeys(
            command.effectKey(), lockIds, true);
        if (releases.size() != lockIds.size()) {
            throw conflict(ERROR_STATE_CONFLICT, "分配锁已释放但释放流水不完整");
        }
        Map<Long, ReleaseRow> releaseByLock = releases.stream().collect(Collectors.toMap(
            ReleaseRow::allocationLockId, Function.identity()));
        for (LockRow lock : domain.allocationLocks()) {
            ReleaseRow release = releaseByLock.get(lock.allocationLockId());
            if (release == null
                || release.evidenceRevision() != command.evidenceRevision()
                || !Objects.equals(release.evidenceRef(), command.evidenceRef())
                || lock.terminalReleaseId() == null
                || lock.terminalReleaseId() != release.releaseId()
                || !Objects.equals(lock.terminalEffectKey(), release.effectKey())
                || !EVIDENCE_NO_EFFECT.equals(lock.effectResult())) {
                throw conflict(ERROR_EFFECT_CONFLICT,
                    "同一释放效果键已绑定不同证据或流水");
            }
        }
        validateReleaseRows(command.identity(), releases);
        List<Long> releasedChannels = domain.channelLocks().stream()
            .filter(channel -> "RELEASED".equals(channel.status()))
            .filter(channel -> Objects.equals(
                channel.terminalEffectKey(), command.effectKey()))
            .map(ChannelLockRow::channelLockId)
            .toList();
        return result(
            command.effectKey(),
            command.evidenceHash(),
            command.evidenceRevision(),
            lockIds,
            releases.stream().map(ReleaseRow::releaseId).toList(),
            releasedChannels
        );
    }

    private List<ReleaseRow> loadReleasesByDerivedKeys(
        String releaseEffectKey,
        List<Long> allocationLockIds,
        boolean forUpdate
    ) {
        List<String> keys = allocationLockIds.stream()
            .map(lockId -> releaseEffectKey(releaseEffectKey, lockId))
            .toList();
        String sql = """
            SELECT release_id, allocation_lock_id, channel_lock_id, task_key,
                   plan_instance_id, evidence_revision, evidence_ref, effect_key,
                   before_status, after_status
            FROM wms_allocation_release
            WHERE effect_key IN (:effectKeys)
            ORDER BY allocation_lock_id
            """ + (forUpdate ? " FOR UPDATE" : "");
        return namedJdbc.query(sql, Map.of("effectKeys", keys),
            (result, index) -> new ReleaseRow(
                result.getLong("release_id"),
                result.getLong("allocation_lock_id"),
                nullableLong(result, "channel_lock_id"),
                result.getString("task_key"),
                result.getLong("plan_instance_id"),
                result.getInt("evidence_revision"),
                result.getString("evidence_ref"),
                result.getString("effect_key"),
                result.getString("before_status"),
                result.getString("after_status")
            ));
    }

    private void verifyPersisted(WmsAllocationReleaseResult result) {
        List<ReleaseRow> releases = loadReleasesByDerivedKeys(
            result.effectKey(), result.allocationLockIds(), false);
        if (!releases.stream().map(ReleaseRow::releaseId).toList()
            .equals(result.releaseIds())) {
            throw error(ERROR_VERIFY_FAILED,
                "释放流水回读与提交结果不一致", HttpStatus.HTTP_CONFLICT);
        }
        Map<Long, ReleaseRow> releaseById = releases.stream().collect(Collectors.toMap(
            ReleaseRow::allocationLockId, Function.identity()));
        List<LockRow> locks = loadAllocationLocks(result.allocationLockIds(), false);
        if (locks.stream().anyMatch(lock -> {
            ReleaseRow release = releaseById.get(lock.allocationLockId());
            return release == null
                || !"RELEASED".equals(lock.status())
                || !EVIDENCE_NO_EFFECT.equals(lock.effectResult())
                || lock.terminalReleaseId() == null
                || lock.terminalReleaseId() != release.releaseId()
                || !Objects.equals(lock.terminalEffectKey(), release.effectKey());
        })) {
            throw error(ERROR_VERIFY_FAILED,
                "分配锁释放终态回读不一致", HttpStatus.HTTP_CONFLICT);
        }
    }

    private String evidenceHash(
        WmsAllocationReleaseIdentity identity,
        String evidenceType,
        String evidenceRef,
        LockedDomain domain
    ) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("businessType", identity.businessType());
        canonical.put("businessId", identity.businessId());
        canonical.put("businessLineId", identity.businessLineId());
        canonical.put("allocationUnitId", identity.allocationUnitId());
        canonical.put("roundNo", identity.roundNo());
        canonical.put("taskKey", identity.taskKey());
        canonical.put("lockTaskKey", identity.lockTaskKey());
        canonical.put("planInstanceId", identity.planInstanceId());
        canonical.put("evidenceType", evidenceType);
        canonical.put("evidenceRef", evidenceRef);
        canonical.put("locks", domain.allocationLocks().stream().map(lock -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("allocationLockId", lock.allocationLockId());
            value.put("channelLockId", lock.channelLockId());
            value.put("storageId", lock.storageId());
            value.put("status", lock.status());
            value.put("effectResult", lock.effectResult());
            value.put("effectKey", lock.effectKey());
            value.put("revision", lock.revision());
            return value;
        }).toList());
        canonical.put("channels", domain.channelLocks().stream().map(channel -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("channelLockId", channel.channelLockId());
            value.put("channelId", channel.channelId());
            value.put("status", channel.status());
            value.put("effectResult", channel.effectResult());
            value.put("effectKey", channel.lockEffectKey());
            value.put("evidenceRevision", channel.evidenceRevision());
            value.put("revision", channel.revision());
            return value;
        }).toList());
        return sha256(jsonMapper.writeValueAsString(canonical));
    }

    private WmsAllocationReleaseResult result(
        String effectKey,
        String evidenceHash,
        int evidenceRevision,
        List<Long> allocationLockIds,
        List<Long> releaseIds,
        List<Long> releasedChannelLockIds
    ) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("status", "RELEASED");
        checkpoint.put("effectKey", effectKey);
        checkpoint.put("evidenceHash", evidenceHash);
        checkpoint.put("evidenceRevision", evidenceRevision);
        checkpoint.put("allocationLockIds", allocationLockIds);
        checkpoint.put("releaseIds", releaseIds);
        checkpoint.put("releasedChannelLockIds", releasedChannelLockIds);
        return new WmsAllocationReleaseResult(
            effectKey,
            evidenceHash,
            evidenceRevision,
            allocationLockIds,
            releaseIds,
            releasedChannelLockIds,
            jsonMapper.writeValueAsString(checkpoint)
        );
    }

    private void validateCheck(WmsAllocationReleaseCheckCommand command) {
        if (command == null || command.identity() == null) {
            throw invalid("释放证据命令和任务身份不能为空");
        }
        validateIdentity(command.identity());
        if (!EVIDENCE_TYPES.contains(command.evidenceType())) {
            throw invalid("释放证据类型只接受NO_EFFECT或RECONCILED_NO_EFFECT");
        }
        requireText(command.evidenceRef(), "evidenceRef", TEXT_LIMIT);
        validateLockIds(command.allocationLockIds());
    }

    private void validateRelease(WmsAllocationReleaseCommand command) {
        if (command == null || command.identity() == null
            || command.expectations() == null || command.expectations().isEmpty()) {
            throw invalid("释放命令、任务身份和锁期望不能为空");
        }
        validateIdentity(command.identity());
        if (command.effectKey() == null
            || !EFFECT_KEY_PATTERN.matcher(command.effectKey()).matches()
            || command.inputHash() == null
            || !HASH_PATTERN.matcher(command.inputHash()).matches()
            || !command.effectKey().equals("WMS:" + command.inputHash())) {
            throw invalid("effectKey必须为WMS:加inputHash，inputHash为64位SHA-256");
        }
        if (command.evidenceHash() == null
            || !HASH_PATTERN.matcher(command.evidenceHash()).matches()
            || command.evidenceRevision() < 1) {
            throw invalid("证据摘要或证据修订号非法");
        }
        if (!EVIDENCE_TYPES.contains(command.normalizedEvidenceType())) {
            throw invalid("释放证据类型只接受NO_EFFECT或RECONCILED_NO_EFFECT");
        }
        requireText(command.evidenceRef(), "evidenceRef", TEXT_LIMIT);
        if (command.occurredAt() == null) {
            throw invalid("occurredAt不能为空");
        }
        optionalText(command.reason(), "reason", TEXT_LIMIT);
        optionalText(command.sourceSystem(), "sourceSystem", 64);
        optionalText(command.requestId(), "requestId", 64);
        validateOptionalPositive(command.eventId(), "eventId");
        validateOptionalPositive(command.attemptId(), "attemptId");
        validateOptionalPositive(command.actorId(), "actorId");
        validateOptionalPositive(command.departmentId(), "departmentId");
        List<Long> lockIds = command.expectations().stream()
            .map(WmsAllocationReleaseLockExpectation::allocationLockId)
            .toList();
        validateLockIds(lockIds);
        Set<String> allowedEffects =
            EVIDENCE_RECONCILED_NO_EFFECT.equals(command.normalizedEvidenceType())
                ? RECONCILABLE_EFFECT_RESULTS : RELEASABLE_EFFECT_RESULTS;
        for (WmsAllocationReleaseLockExpectation item : command.expectations()) {
            if (item.revision() < 1
                || !allowedEffects.contains(item.effectResult())) {
                throw invalid("释放锁期望状态非法: " + item.allocationLockId());
            }
        }
    }

    private void validateIdentity(WmsAllocationReleaseIdentity identity) {
        requireText(identity.businessType(), "businessType", 64);
        requireText(identity.businessName(), "businessName", 128);
        requirePositive(identity.businessId(), "businessId");
        optionalText(identity.businessNo(), "businessNo", 100);
        validateOptionalPositive(identity.businessLineId(), "businessLineId");
        requirePositive(identity.allocationUnitId(), "allocationUnitId");
        if (identity.roundNo() < 1) {
            throw invalid("roundNo必须大于等于1");
        }
        requireText(identity.taskKey(), "taskKey", 96);
        requireText(identity.lockTaskKey(), "lockTaskKey", 96);
        requirePositive(identity.planInstanceId(), "planInstanceId");
    }

    private void validateVerify(WmsAllocationReleaseVerifyCommand command) {
        if (command == null
            || command.effectKey() == null
            || !EFFECT_KEY_PATTERN.matcher(command.effectKey()).matches()
            || command.evidenceHash() == null
            || !HASH_PATTERN.matcher(command.evidenceHash()).matches()) {
            throw invalid("核验释放效果键非法");
        }
        if (command.identity() == null) {
            throw invalid("核验释放任务身份不能为空");
        }
        validateIdentity(command.identity());
        validateLockIds(command.allocationLockIds());
    }

    private void validateVerifyIdentity(
        WmsAllocationReleaseIdentity identity,
        List<LockRow> locks
    ) {
        for (LockRow lock : locks) {
            if (!Objects.equals(identity.businessType(), lock.businessType())
                || identity.businessId() != lock.businessId()
                || !Objects.equals(identity.businessLineId(), lock.businessLineId())
                || identity.allocationUnitId() != lock.allocationUnitId()
                || identity.roundNo() != lock.roundNo()
                || !Objects.equals(identity.lockTaskKey(), lock.taskKey())) {
                throw error(ERROR_VERIFY_FAILED,
                    "释放核验锁持有人身份不一致", HttpStatus.HTTP_CONFLICT);
            }
        }
    }

    private void validateReleaseRows(
        WmsAllocationReleaseIdentity identity,
        List<ReleaseRow> releases
    ) {
        for (ReleaseRow release : releases) {
            if (!Objects.equals(identity.taskKey(), release.taskKey())
                || identity.planInstanceId() != release.planInstanceId()) {
                throw error(ERROR_VERIFY_FAILED,
                    "释放流水与释放任务身份不一致", HttpStatus.HTTP_CONFLICT);
            }
        }
    }

    private void validateLockIds(List<Long> lockIds) {
        if (lockIds == null || lockIds.isEmpty()
            || lockIds.stream().anyMatch(id -> id == null || id <= 0)
            || new HashSet<>(lockIds).size() != lockIds.size()) {
            throw invalid("分配锁ID不能为空、重复或非正");
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + "不能为空且长度不能超过" + maxLength);
        }
    }

    private void optionalText(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw invalid(field + "长度不能超过" + maxLength);
        }
    }

    private void requirePositive(long value, String field) {
        if (value <= 0) {
            throw invalid(field + "必须为正整数");
        }
    }

    private void validateOptionalPositive(Long value, String field) {
        if (value != null && value <= 0) {
            throw invalid(field + "必须为正整数或为null");
        }
    }

    private long nextId() {
        Number generated = identifierGenerator.nextId(null);
        if (generated == null || generated.longValue() <= 0) {
            throw new IllegalStateException("释放主键生成失败");
        }
        return generated.longValue();
    }

    private String releaseEffectKey(String effectKey, long allocationLockId) {
        return "WMS:" + sha256(effectKey + "|RELEASE_LOCK:" + allocationLockId);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM缺少SHA-256", error);
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
        String businessName,
        long businessId,
        String businessNo,
        Long businessLineId,
        long allocationUnitId,
        int roundNo,
        String taskKey,
        long planInstanceId,
        long storageId,
        String status,
        String effectResult,
        String effectKey,
        int evidenceRevision,
        String terminalEffectKey,
        Long terminalReleaseId,
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
        String lockEffectKey,
        String terminalEffectKey,
        int evidenceRevision,
        int revision
    ) {
    }

    private record ReleaseRow(
        long releaseId,
        long allocationLockId,
        Long channelLockId,
        String taskKey,
        long planInstanceId,
        int evidenceRevision,
        String evidenceRef,
        String effectKey,
        String beforeStatus,
        String afterStatus
    ) {
    }
}
