/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包草稿创建、发布校验、生效指针 CAS 与逻辑归属快照实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.service.impl;

import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.wms.domain.WmsCommandAttempt;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.domain.WmsLogicalScope;
import com.innolux.iws.wms.rule.domain.WmsLogicalScopeStorage;
import com.innolux.iws.wms.rule.domain.WmsLogicalScopeVersion;
import com.innolux.iws.wms.rule.domain.WmsRulePublicationValidation;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleEffectivePointer;
import com.innolux.iws.wms.rule.domain.WmsRulePackageMember;
import com.innolux.iws.wms.rule.domain.WmsRulePackageVersion;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackageCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageMemberVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeMapper;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeStorageMapper;
import com.innolux.iws.wms.rule.mapper.WmsLogicalScopeVersionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleDefinitionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleEffectivePointerMapper;
import com.innolux.iws.wms.rule.mapper.WmsRulePackageMemberMapper;
import com.innolux.iws.wms.rule.mapper.WmsRulePackageVersionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleVersionMapper;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import com.innolux.iws.wms.rule.service.WmsRuleQueryService;
import com.innolux.iws.wms.rule.support.WmsRuleAuditService;
import com.innolux.iws.wms.rule.support.WmsRuleCommandExecutor;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.rule.support.WmsRuleScopeGuard;
import com.innolux.iws.wms.rule.support.WmsRuleValidation;
import com.innolux.iws.wms.rule.support.WmsRuleViewSupport;
import com.innolux.iws.wms.process.WmsExecutionActor;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 规则包草稿与发布实现。
 *
 * <p>发布顺序固定为：排他锁定生效指针行 → 按 ID 升序共享锁定成员定义 → 校验包与成员版本 →
 * 校验摘要未变 → 激活包版本 → 生成逻辑归属版本与储位快照 → 指针 CAS 切换 →
 * 同事务读取切换结果。任一校验失败在事务内抛出，包状态、逻辑归属与指针全部回滚。</p>
 *
 * <p>逻辑归属按 {@code scopeType:scopeId} 稳定编码复用，同一作用域重复发布递增
 * {@code wms_logical_scope_version.version_no} 并推进 {@code current_version_id}。</p>
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class WmsRulePublishServiceImpl implements WmsRulePublishService {

    private static final String ERROR_PACKAGE_SCOPE_MISMATCH = "RULE_PACKAGE_SCOPE_MISMATCH";

    private final WmsRuleDefinitionMapper definitionMapper;

    private final WmsRuleVersionMapper ruleVersionMapper;

    private final WmsRulePackageVersionMapper packageVersionMapper;

    private final WmsRulePackageMemberMapper packageMemberMapper;

    private final WmsRuleEffectivePointerMapper pointerMapper;

    private final WmsLogicalScopeMapper logicalScopeMapper;

    private final WmsLogicalScopeVersionMapper logicalScopeVersionMapper;

    private final WmsLogicalScopeStorageMapper logicalScopeStorageMapper;

    private final WmsRuleQueryService queryService;

    private final WmsRuleAuditService auditService;

    private final WmsRuleCommandExecutor commandExecutor;

    private final WmsRuleBindingService bindingService;

    private final WmsRuleScopeGuard scopeGuard;

    private final WmsCompanyAccessPolicy accessPolicy;

    @Override
    public WmsRulePackageVersionVo createPackageVersion(WmsRulePackageCreateCommand command) {
        if (command == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        String commandKey = WmsRuleValidation.requireCommandKey(command.getCommandKey());
        String packageCode = WmsRuleValidation.requiredText(command.getPackageCode(), "packageCode", 64);
        List<WmsRulePackageCreateCommand.Member> members = requireMembers(command.getMembers());
        return commandExecutor.execute(
            WmsRuleContract.ACTION_PACKAGE_CREATE,
            WmsRuleContract.OBJECT_TYPE_PACKAGE,
            null,
            commandKey,
            command,
            Isolation.READ_COMMITTED,
            () -> doCreatePackageVersion(command, packageCode, members),
            () -> replayPackage(commandKey)
        );
    }

    private WmsRulePackageVersionVo doCreatePackageVersion(WmsRulePackageCreateCommand command,
                                                           String packageCode,
                                                           List<WmsRulePackageCreateCommand.Member> members) {
        accessPolicy.checkPermissionForShare(WmsRuleContract.PERMISSION_PUBLISH);
        if (!auditService.assertReusable(command.getCommandKey(), auditService.digest(command),
            WmsRuleContract.ACTION_PACKAGE_CREATE)) {
            return replayPackage(command.getCommandKey());
        }

        List<WmsRuleVersion> versions = members.stream().map(member -> {
            WmsRuleVersion version = ruleVersionMapper.selectById(member.getRuleVersionId());
            if (version == null) {
                throw versionNotFound(member.getRuleVersionId());
            }
            return version;
        }).toList();
        Map<Long, WmsRuleDefinition> definitions = lockDefinitions(versions);

        WmsRulePackageVersion latest = packageVersionMapper.selectLatestByCodeForUpdate(packageCode);
        int nextVersionNo = latest == null ? 1 : latest.getVersionNo() + 1;

        List<WmsRulePackageMember> memberRows = new ArrayList<>(members.size());
        List<WmsRuleDigest.PackageMemberDigest> digestInputs = new ArrayList<>(members.size());
        String packageScopeType = null;
        for (int index = 0; index < members.size(); index++) {
            WmsRulePackageCreateCommand.Member member = members.get(index);
            WmsRuleVersion version = versions.get(index);
            WmsRuleDefinition definition = definitions.get(version.getRuleDefinitionId());
            assertDefinitionActive(definition);
            if (packageScopeType == null) {
                packageScopeType = definition.getScopeType();
            } else {
                assertSameScope(packageScopeType, definition);
            }
            if (!member.getPurpose().equals(definition.getPurpose())) {
                throw packagePurposeMismatch(member.getPurpose(), definition.getPurpose());
            }
            if (!WmsRuleContract.STATUS_ACTIVE.equals(version.getStatus())) {
                throw new ServiceException(WmsRuleContract.ERROR_VERSION_NOT_ACTIVE + ": 规则版本尚未激活",
                    HttpStatus.HTTP_CONFLICT);
            }
            bindingService.requireReady(version, definition);
            WmsRulePackageMember row = new WmsRulePackageMember();
            row.setPurpose(member.getPurpose());
            row.setRuleVersionId(version.getRuleVersionId());
            row.setRevision(1);
            memberRows.add(row);
            digestInputs.add(new WmsRuleDigest.PackageMemberDigest(
                member.getPurpose(), version.getRuleVersionId(), version.getCanonicalDigest()
            ));
        }
        String canonicalDigest = WmsRuleDigest.packageDigest(packageCode, digestInputs);

        WmsRulePackageVersion packageVersion = new WmsRulePackageVersion();
        packageVersion.setPackageCode(packageCode);
        packageVersion.setVersionNo(nextVersionNo);
        packageVersion.setCanonicalDigest(canonicalDigest);
        packageVersion.setStatus(WmsRuleContract.STATUS_DRAFT);
        try {
            packageVersionMapper.insert(packageVersion);
        } catch (DuplicateKeyException error) {
            throw new ServiceException(
                WmsRuleContract.ERROR_PACKAGE_VERSION_CONFLICT + ": 规则包版本号已被并发占用",
                HttpStatus.HTTP_CONFLICT
            );
        }
        for (WmsRulePackageMember row : memberRows) {
            row.setPackageVersionId(packageVersion.getPackageVersionId());
            try {
                packageMemberMapper.insert(row);
            } catch (DuplicateKeyException error) {
                throw new ServiceException(
                    WmsRuleContract.ERROR_PACKAGE_PURPOSE_DUPLICATE + ": 成员用途重复",
                    HttpStatus.HTTP_CONFLICT
                );
            }
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("packageVersionId", packageVersion.getPackageVersionId());
        after.put("packageCode", packageCode);
        after.put("versionNo", nextVersionNo);
        after.put("canonicalDigest", canonicalDigest);
        after.put("status", WmsRuleContract.STATUS_DRAFT);
        after.put("purposes", memberRows.stream().map(WmsRulePackageMember::getPurpose).toList());
        auditService.recordSuccess(
            WmsRuleContract.ACTION_PACKAGE_CREATE,
            WmsRuleContract.OBJECT_TYPE_PACKAGE,
            packageVersion.getPackageVersionId(),
            command.getCommandKey(),
            command,
            null,
            after,
            command.getReason(),
            LocalDateTime.now(),
            WmsRuleContract.FACT_TYPE_PACKAGE,
            String.valueOf(packageVersion.getPackageVersionId())
        );
        return loadPackage(packageVersion.getPackageVersionId());
    }

    @Override
    public WmsRuleEffectiveVo publish(WmsRulePackagePublishCommand command) {
        PublishCommandValues values = normalize(command);
        return commandExecutor.execute(
            WmsRuleContract.ACTION_PACKAGE_PUBLISH,
            WmsRuleContract.OBJECT_TYPE_PACKAGE,
            values.packageVersionId(),
            values.commandKey(),
            command,
            Isolation.READ_COMMITTED,
            () -> doPublish(
                command,
                values.packageVersionId(),
                values.scopeType(),
                values.scopeId(),
                values.storageIds()
            ),
            () -> queryService.getEffectiveForPublish(values.scopeType(), values.scopeId())
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsRulePublicationValidation validateWithinTransaction(
        WmsRulePackagePublishCommand command
    ) {
        return validateWithinTransaction(command, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsRulePublicationValidation validateWithinTransaction(
        WmsRulePackagePublishCommand command,
        WmsExecutionActor actor
    ) {
        PublishCommandValues values = normalize(command);
        Long actorId = actor == null ? LoginHelper.getUserId() : actor.userId();
        accessPolicy.checkPermissionForShare(WmsRuleContract.PERMISSION_PUBLISH, actorId);
        WmsRuleEffectivePointer pointer = pointerMapper.selectByScopeForUpdate(
            values.scopeType(),
            values.scopeId()
        );
        PreparedPublication prepared = preparePublication(
            values.packageVersionId(),
            values.scopeType(),
            values.scopeId(),
            values.storageIds(),
            actorId
        );
        return new WmsRulePublicationValidation(
            values.packageVersionId(),
            values.scopeType(),
            values.scopeId(),
            prepared.packageDigest(),
            prepared.resolvedScope().storageIds(),
            pointer == null ? null : pointer.getPackageVersionId(),
            pointer == null ? null : pointer.getRevision()
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsRuleEffectiveVo publishWithinTransaction(WmsRulePackagePublishCommand command) {
        return publishWithinTransaction(command, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WmsRuleEffectiveVo publishWithinTransaction(
        WmsRulePackagePublishCommand command,
        WmsExecutionActor actor
    ) {
        PublishCommandValues values = normalize(command);
        return doPublish(
            command,
            values.packageVersionId(),
            values.scopeType(),
            values.scopeId(),
            values.storageIds(),
            actor
        );
    }

    private WmsRuleEffectiveVo doPublish(WmsRulePackagePublishCommand command, Long packageVersionId,
                                         String scopeType, Long scopeId, List<Long> storageIds) {
        return doPublish(command, packageVersionId, scopeType, scopeId, storageIds, null);
    }

    private WmsRuleEffectiveVo doPublish(
        WmsRulePackagePublishCommand command,
        Long packageVersionId,
        String scopeType,
        Long scopeId,
        List<Long> storageIds,
        WmsExecutionActor actor
    ) {
        Long actorId = actor == null ? LoginHelper.getUserId() : actor.userId();
        accessPolicy.checkPermissionForShare(WmsRuleContract.PERMISSION_PUBLISH, actorId);
        if (!auditService.assertReusable(command.getCommandKey(), auditService.digest(command),
            WmsRuleContract.ACTION_PACKAGE_PUBLISH)) {
            return queryService.getEffectiveForPublish(scopeType, scopeId, actorId);
        }

        WmsRuleEffectivePointer pointer = pointerMapper.selectByScopeForUpdate(scopeType, scopeId);
        PreparedPublication prepared = preparePublication(
            packageVersionId,
            scopeType,
            scopeId,
            storageIds,
            actorId
        );
        WmsRuleScopeGuard.ResolvedScope resolvedScope = prepared.resolvedScope();
        String packageDigest = prepared.packageDigest();

        LocalDateTime occurredAt = LocalDateTime.now();
        Long userId = actorId;
        int activated = packageVersionMapper.update(
            null,
            new LambdaUpdateWrapper<WmsRulePackageVersion>()
                .eq(WmsRulePackageVersion::getPackageVersionId, packageVersionId)
                .eq(WmsRulePackageVersion::getStatus, WmsRuleContract.STATUS_DRAFT)
                .set(WmsRulePackageVersion::getStatus, WmsRuleContract.STATUS_ACTIVE)
                .set(WmsRulePackageVersion::getPublishedAt, occurredAt)
                .set(WmsRulePackageVersion::getPublishedBy, userId)
                .set(WmsRulePackageVersion::getUpdateBy, userId)
                .set(WmsRulePackageVersion::getUpdateTime, occurredAt)
        );
        if (activated != 1) {
            throw new ServiceException(
                WmsRuleContract.ERROR_PACKAGE_NOT_DRAFT + ": 规则包版本状态已被并发修改",
                HttpStatus.HTTP_CONFLICT
            );
        }

        WmsLogicalScopeVersion scopeVersion =
            appendLogicalScopeVersion(scopeType, scopeId, packageVersionId,
                resolvedScope.storageIds(), occurredAt, userId);
        int pointerRevision = switchEffectivePointer(
            pointer, scopeType, scopeId, packageVersionId, occurredAt, userId
        );

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("scopeType", scopeType);
        before.put("scopeId", scopeId);
        before.put("pointerRevision", pointer == null ? null : pointer.getRevision());
        before.put("packageVersionId", pointer == null ? null : pointer.getPackageVersionId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("scopeType", scopeType);
        after.put("scopeId", scopeId);
        after.put("pointerRevision", pointerRevision);
        after.put("packageVersionId", packageVersionId);
        after.put("canonicalDigest", packageDigest);
        after.put("scopeVersionId", scopeVersion.getScopeVersionId());
        after.put("scopeVersionNo", scopeVersion.getVersionNo());
        after.put("spaceSnapshotDigest", scopeVersion.getSpaceSnapshotDigest());
        auditService.recordSuccess(
            WmsRuleContract.ACTION_PACKAGE_PUBLISH,
            WmsRuleContract.OBJECT_TYPE_PACKAGE,
            packageVersionId,
            command.getCommandKey(),
            command,
            before,
            after,
            command.getReason(),
            occurredAt,
            WmsRuleContract.FACT_TYPE_EFFECTIVE,
            String.valueOf(scopeVersion.getScopeVersionId()),
            actor
        );
        return queryService.getEffectiveForPublish(scopeType, scopeId, actorId);
    }

    private PreparedPublication preparePublication(
        Long packageVersionId,
        String scopeType,
        Long scopeId,
        List<Long> storageIds,
        Long actorId
    ) {
        WmsRulePackageVersion packageVersion = packageVersionMapper.selectById(packageVersionId);
        if (packageVersion == null) {
            throw new ServiceException(
                WmsRuleContract.ERROR_PACKAGE_NOT_FOUND + ": " + packageVersionId,
                HttpStatus.HTTP_NOT_FOUND
            );
        }
        if (!WmsRuleContract.STATUS_DRAFT.equals(packageVersion.getStatus())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_PACKAGE_NOT_DRAFT + ": 只有 DRAFT 规则包版本可以发布",
                HttpStatus.HTTP_CONFLICT
            );
        }
        List<WmsRulePackageMember> members = packageMemberMapper.selectList(
            new LambdaQueryWrapper<WmsRulePackageMember>()
                .eq(WmsRulePackageMember::getPackageVersionId, packageVersionId)
                .orderByAsc(WmsRulePackageMember::getPurpose)
        );
        if (members.isEmpty()) {
            throw packageMemberRequired();
        }
        List<WmsRuleVersion> versions = members.stream().map(member -> {
            WmsRuleVersion version = ruleVersionMapper.selectById(member.getRuleVersionId());
            if (version == null) {
                throw versionNotFound(member.getRuleVersionId());
            }
            if (!WmsRuleContract.STATUS_ACTIVE.equals(version.getStatus())) {
                throw new ServiceException(
                    WmsRuleContract.ERROR_VERSION_NOT_ACTIVE
                        + ": 规则版本 " + version.getRuleVersionId()
                        + " 状态为 " + version.getStatus(),
                    HttpStatus.HTTP_CONFLICT
                );
            }
            return version;
        }).toList();
        Map<Long, WmsRuleDefinition> definitions = lockDefinitions(versions);
        List<WmsRuleDigest.PackageMemberDigest> digestInputs = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            WmsRulePackageMember member = members.get(index);
            WmsRuleVersion version = versions.get(index);
            WmsRuleDefinition definition = definitions.get(version.getRuleDefinitionId());
            assertDefinitionActive(definition);
            assertSameScope(scopeType, definition);
            if (!member.getPurpose().equals(definition.getPurpose())) {
                throw packagePurposeMismatch(member.getPurpose(), definition.getPurpose());
            }
            bindingService.requireReady(version, definition);
            digestInputs.add(new WmsRuleDigest.PackageMemberDigest(
                member.getPurpose(), version.getRuleVersionId(), version.getCanonicalDigest()
            ));
        }
        String packageDigest = WmsRuleDigest.packageDigest(
            packageVersion.getPackageCode(),
            digestInputs
        );
        if (!packageDigest.equals(packageVersion.getCanonicalDigest())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_PACKAGE_DIGEST_MISMATCH + ": 规则包成员与存储摘要不一致",
                HttpStatus.HTTP_CONFLICT
            );
        }
        WmsRuleScopeGuard.ResolvedScope resolvedScope =
            scopeGuard.lockForPublish(scopeType, scopeId, storageIds, actorId);
        return new PreparedPublication(packageDigest, resolvedScope);
    }

    private WmsLogicalScopeVersion appendLogicalScopeVersion(String scopeType, Long scopeId,
                                                             Long packageVersionId, List<Long> storageIds,
                                                             LocalDateTime occurredAt, Long userId) {
        String scopeCode = WmsRuleContract.scopeCode(scopeType, scopeId);
        WmsLogicalScope logicalScope = logicalScopeMapper.selectByCodeForUpdate(scopeCode);
        if (logicalScope == null) {
            WmsLogicalScope created = new WmsLogicalScope();
            created.setScopeCode(scopeCode);
            created.setScopeName(scopeCode);
            created.setLifecycleStatus(WmsRuleContract.LIFECYCLE_ACTIVE);
            created.setRevision(1);
            try {
                logicalScopeMapper.insert(created);
            } catch (DuplicateKeyException error) {
                throw new ServiceException(
                    WmsRuleContract.ERROR_POINTER_CONFLICT + ": 同一作用域并发创建逻辑归属",
                    HttpStatus.HTTP_CONFLICT
                );
            }
            logicalScope = created;
        }

        Integer maxVersionNo = logicalScopeVersionMapper.selectMaxVersionNo(logicalScope.getLogicalScopeId());
        int nextVersionNo = (maxVersionNo == null ? 0 : maxVersionNo) + 1;
        List<Long> orderedStorageIds = new ArrayList<>(new LinkedHashSet<>(storageIds));
        orderedStorageIds.sort(Long::compareTo);

        WmsLogicalScopeVersion scopeVersion = new WmsLogicalScopeVersion();
        scopeVersion.setLogicalScopeId(logicalScope.getLogicalScopeId());
        scopeVersion.setVersionNo(nextVersionNo);
        scopeVersion.setRulePackageVersionId(packageVersionId);
        scopeVersion.setSpaceSnapshotDigest(WmsRuleDigest.spaceSnapshotDigest(orderedStorageIds));
        scopeVersion.setStatus(WmsRuleContract.STATUS_ACTIVE);
        scopeVersion.setPublishedAt(occurredAt);
        scopeVersion.setPublishedBy(userId);
        logicalScopeVersionMapper.insert(scopeVersion);
        for (Long storageId : orderedStorageIds) {
            WmsLogicalScopeStorage storage = new WmsLogicalScopeStorage();
            storage.setScopeVersionId(scopeVersion.getScopeVersionId());
            storage.setStorageId(storageId);
            storage.setRevision(1);
            logicalScopeStorageMapper.insert(storage);
        }

        int updated = logicalScopeMapper.update(
            null,
            new LambdaUpdateWrapper<WmsLogicalScope>()
                .eq(WmsLogicalScope::getLogicalScopeId, logicalScope.getLogicalScopeId())
                .eq(WmsLogicalScope::getRevision, logicalScope.getRevision())
                .set(WmsLogicalScope::getCurrentVersionId, scopeVersion.getScopeVersionId())
                .set(WmsLogicalScope::getRevision, logicalScope.getRevision() + 1)
                .set(WmsLogicalScope::getUpdateBy, userId)
                .set(WmsLogicalScope::getUpdateTime, occurredAt)
        );
        if (updated != 1) {
            throw new ServiceException(
                WmsRuleContract.ERROR_VERSION_CONFLICT + ": 逻辑归属修订已变化",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return scopeVersion;
    }

    private int switchEffectivePointer(WmsRuleEffectivePointer pointer, String scopeType, Long scopeId,
                                       Long packageVersionId, LocalDateTime occurredAt, Long userId) {
        if (pointer == null) {
            WmsRuleEffectivePointer created = new WmsRuleEffectivePointer();
            created.setScopeType(scopeType);
            created.setScopeId(scopeId);
            created.setPackageVersionId(packageVersionId);
            created.setRevision(1);
            try {
                pointerMapper.insert(created);
            } catch (DuplicateKeyException error) {
                throw new ServiceException(
                    WmsRuleContract.ERROR_POINTER_CONFLICT + ": 同一作用域并发生效切换",
                    HttpStatus.HTTP_CONFLICT
                );
            }
            return 1;
        }
        int nextRevision = pointer.getRevision() + 1;
        int updated = pointerMapper.update(
            null,
            new LambdaUpdateWrapper<WmsRuleEffectivePointer>()
                .eq(WmsRuleEffectivePointer::getRuleEffectivePointerId, pointer.getRuleEffectivePointerId())
                .eq(WmsRuleEffectivePointer::getRevision, pointer.getRevision())
                .eq(WmsRuleEffectivePointer::getScopeType, scopeType)
                .eq(WmsRuleEffectivePointer::getScopeId, scopeId)
                .set(WmsRuleEffectivePointer::getPackageVersionId, packageVersionId)
                .set(WmsRuleEffectivePointer::getRevision, nextRevision)
                .set(WmsRuleEffectivePointer::getUpdateBy, userId)
                .set(WmsRuleEffectivePointer::getUpdateTime, occurredAt)
        );
        if (updated != 1) {
            throw new ServiceException(
                WmsRuleContract.ERROR_POINTER_CONFLICT + ": 生效指针已被并发切换",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return nextRevision;
    }

    private WmsRulePackageVersionVo loadPackage(Long packageVersionId) {
        WmsRulePackageVersion packageVersion = packageVersionMapper.selectById(packageVersionId);
        if (packageVersion == null) {
            throw new ServiceException(
                WmsRuleContract.ERROR_PACKAGE_NOT_FOUND + ": " + packageVersionId,
                HttpStatus.HTTP_NOT_FOUND
            );
        }
        List<WmsRulePackageMemberVo> members = packageMemberMapper.selectList(
            new LambdaQueryWrapper<WmsRulePackageMember>()
                .eq(WmsRulePackageMember::getPackageVersionId, packageVersionId)
                .orderByAsc(WmsRulePackageMember::getPurpose)
        ).stream().map(this::toPackageMemberVo).toList();
        return WmsRuleViewSupport.toPackageVo(packageVersion, members);
    }

    private WmsRulePackageMemberVo toPackageMemberVo(WmsRulePackageMember member) {
        WmsRuleVersion version = ruleVersionMapper.selectById(member.getRuleVersionId());
        WmsRuleDefinition definition = version == null
            ? null : definitionMapper.selectById(version.getRuleDefinitionId());
        return new WmsRulePackageMemberVo(
            member.getPurpose(),
            member.getRuleVersionId(),
            member.getRevision(),
            definition == null ? null : definition.getScopeType(),
            bindingService.toVersionVo(version)
        );
    }

    private WmsRulePackageVersionVo replayPackage(String commandKey) {
        WmsCommandAttempt attempt = auditService.findAttempt(commandKey);
        if (attempt == null || attempt.getFactId() == null) {
            throw new ServiceException(
                "COMMAND_REPLAY_UNAVAILABLE: 已生效命令缺少可读回事实",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return loadPackage(Long.valueOf(attempt.getFactId()));
    }

    private List<WmsRulePackageCreateCommand.Member> requireMembers(
        List<WmsRulePackageCreateCommand.Member> members) {
        if (members == null || members.isEmpty()) {
            throw packageMemberRequired();
        }
        List<WmsRulePackageCreateCommand.Member> normalized = new ArrayList<>(members.size());
        LinkedHashSet<String> purposes = new LinkedHashSet<>();
        for (WmsRulePackageCreateCommand.Member member : members) {
            if (member == null) {
                throw WmsRuleValidation.invalid("members 不能包含空条目");
            }
            String purpose = WmsRuleValidation.requiredText(member.getPurpose(), "purpose", 32);
            Long ruleVersionId = WmsRuleValidation.requireId(member.getRuleVersionId(), "ruleVersionId");
            if (!purposes.add(purpose)) {
                throw packagePurposeDuplicate(purpose);
            }
            WmsRulePackageCreateCommand.Member normalizedMember = new WmsRulePackageCreateCommand.Member();
            normalizedMember.setPurpose(purpose);
            normalizedMember.setRuleVersionId(ruleVersionId);
            normalized.add(normalizedMember);
        }
        return normalized;
    }

    private PublishCommandValues normalize(WmsRulePackagePublishCommand command) {
        if (command == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        String commandKey = WmsRuleValidation.requireCommandKey(command.getCommandKey());
        Long packageVersionId =
            WmsRuleValidation.requireId(command.getPackageVersionId(), "packageVersionId");
        String scopeType = scopeGuard.requireScopeType(
            WmsRuleValidation.requireScopeType(command.getScopeType())
        );
        Long scopeId = WmsRuleValidation.requireId(command.getScopeId(), "scopeId");
        List<Long> storageIds = WmsRuleValidation.requireStorageIds(command.getStorageIds());
        return new PublishCommandValues(
            commandKey,
            packageVersionId,
            scopeType,
            scopeId,
            storageIds
        );
    }

    private Map<Long, WmsRuleDefinition> lockDefinitions(List<WmsRuleVersion> versions) {
        List<Long> definitionIds = versions.stream()
            .map(WmsRuleVersion::getRuleDefinitionId)
            .distinct()
            .sorted()
            .toList();
        Map<Long, WmsRuleDefinition> definitions = new LinkedHashMap<>();
        for (Long definitionId : definitionIds) {
            WmsRuleDefinition definition = definitionMapper.selectByIdForShare(definitionId);
            if (definition == null) {
                throw definitionNotFound(definitionId);
            }
            definitions.put(definitionId, definition);
        }
        return definitions;
    }

    private void assertSameScope(String expectedScopeType, WmsRuleDefinition definition) {
        if (!expectedScopeType.equals(definition.getScopeType())) {
            throw new ServiceException(
                ERROR_PACKAGE_SCOPE_MISMATCH + ": 规则定义 " + definition.getRuleDefinitionId()
                    + " 作用域 " + definition.getScopeType() + " 与规则包作用域 "
                    + expectedScopeType + " 不一致",
                HttpStatus.HTTP_CONFLICT
            );
        }
    }

    private ServiceException packagePurposeMismatch(String memberPurpose, String definitionPurpose) {
        return new ServiceException(
            WmsRuleContract.ERROR_PACKAGE_PURPOSE_MISMATCH
                + ": 成员用途 " + memberPurpose + " 与规则用途 " + definitionPurpose + " 不一致",
            HttpStatus.HTTP_CONFLICT
        );
    }

    private ServiceException packageMemberRequired() {
        return new ServiceException(
            WmsRuleContract.ERROR_PACKAGE_MEMBER_REQUIRED + ": 规则包至少需要一个成员",
            HttpStatus.HTTP_CONFLICT
        );
    }

    private ServiceException packagePurposeDuplicate(String purpose) {
        return new ServiceException(
            WmsRuleContract.ERROR_PACKAGE_PURPOSE_DUPLICATE + ": 成员用途重复: " + purpose,
            HttpStatus.HTTP_CONFLICT
        );
    }

    private void assertDefinitionActive(WmsRuleDefinition definition) {
        if (!WmsRuleContract.DEFINITION_STATUS_ACTIVE.equals(definition.getStatus())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_DEFINITION_NOT_ACTIVE + ": 规则定义已停用，不再接受新引用",
                HttpStatus.HTTP_CONFLICT
            );
        }
    }

    private ServiceException versionNotFound(Long ruleVersionId) {
        return new ServiceException(
            WmsRuleContract.ERROR_VERSION_NOT_FOUND + ": " + ruleVersionId,
            HttpStatus.HTTP_NOT_FOUND
        );
    }

    private ServiceException definitionNotFound(Long ruleDefinitionId) {
        return new ServiceException(
            WmsRuleContract.ERROR_DEFINITION_NOT_FOUND + ": " + ruleDefinitionId,
            HttpStatus.HTTP_NOT_FOUND
        );
    }

    private record PublishCommandValues(
        String commandKey,
        Long packageVersionId,
        String scopeType,
        Long scopeId,
        List<Long> storageIds
    ) {
    }

    private record PreparedPublication(
        String packageDigest,
        WmsRuleScopeGuard.ResolvedScope resolvedScope
    ) {
    }
}
