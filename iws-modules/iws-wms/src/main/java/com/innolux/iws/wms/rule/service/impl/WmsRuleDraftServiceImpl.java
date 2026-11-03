/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则定义创建、草稿编辑/复制、版本激活与定义退役实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.http.HttpStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.wms.domain.WmsCommandAttempt;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.domain.WmsRuleDefinition;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionRetireCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDraftEditCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionActivateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionCopyCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.mapper.WmsRuleDefinitionMapper;
import com.innolux.iws.wms.rule.mapper.WmsRuleVersionMapper;
import com.innolux.iws.wms.rule.service.WmsRuleDraftService;
import com.innolux.iws.wms.rule.support.WmsRuleAuditService;
import com.innolux.iws.wms.rule.support.WmsRuleCommandExecutor;
import com.innolux.iws.wms.rule.support.WmsRuleDigest;
import com.innolux.iws.wms.rule.support.WmsRuleValidation;
import com.innolux.iws.wms.rule.support.WmsRuleViewSupport;
import com.innolux.iws.wms.rule.binding.WmsRuleBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则定义与版本草稿管理实现。
 *
 * <p>命令使用独立事务边界、命令键幂等和审计留证；草稿表没有 revision 列，编辑与激活用存储的
 * {@code canonical_digest} 作为乐观并发令牌。所有摘要都在写入后按 MySQL JSON 列回读内容重算，
 * 保证持久摘要始终可由存储内容复现，发布时的“摘要未变”校验才有意义。</p>
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class WmsRuleDraftServiceImpl implements WmsRuleDraftService {

    private final WmsRuleDefinitionMapper definitionMapper;

    private final WmsRuleVersionMapper versionMapper;

    private final WmsRuleAuditService auditService;

    private final WmsRuleCommandExecutor commandExecutor;

    private final WmsRuleBindingService bindingService;

    @Override
    public WmsRuleDefinitionVo createDefinition(WmsRuleDefinitionCreateCommand command) {
        if (command == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        String commandKey = WmsRuleValidation.requireCommandKey(command.getCommandKey());
        String ruleKey = WmsRuleValidation.requiredText(command.getRuleKey(), "ruleKey", 64);
        String purpose = WmsRuleValidation.requiredText(command.getPurpose(), "purpose", 32);
        String ruleName = WmsRuleValidation.requiredText(command.getRuleName(), "ruleName", 128);
        String scopeType = WmsRuleValidation.requiredText(command.getScopeType(), "scopeType", 32);
        JsonNode condition = WmsRuleValidation.requireJson(command.getCondition(), "condition");
        JsonNode result = WmsRuleValidation.requireJson(command.getResult(), "result");
        String digest = WmsRuleDigest.ruleVersionDigest(condition, result, null);
        return commandExecutor.execute(
            WmsRuleContract.ACTION_DEFINITION_CREATE,
            WmsRuleContract.OBJECT_TYPE_DEFINITION,
            null,
            commandKey,
            command,
            Isolation.READ_COMMITTED,
            () -> doCreateDefinition(command, ruleKey, purpose, ruleName, scopeType, condition, result, digest),
            () -> replayDefinition(commandKey)
        );
    }

    private WmsRuleDefinitionVo doCreateDefinition(WmsRuleDefinitionCreateCommand command, String ruleKey,
                                                   String purpose, String ruleName, String scopeType,
                                                   JsonNode condition, JsonNode result, String digest) {
        StpUtil.checkPermission(WmsRuleContract.PERMISSION_DRAFT);
        if (!auditService.assertReusable(command.getCommandKey(), auditService.digest(command),
            WmsRuleContract.ACTION_DEFINITION_CREATE)) {
            return replayDefinition(command.getCommandKey());
        }
        Long duplicates = definitionMapper.selectCount(
            new LambdaQueryWrapper<WmsRuleDefinition>().eq(WmsRuleDefinition::getRuleKey, ruleKey)
        );
        if (duplicates != null && duplicates > 0) {
            throw new ServiceException(
                WmsRuleContract.ERROR_RULE_KEY_EXISTS + ": 规则键已存在",
                HttpStatus.HTTP_CONFLICT
            );
        }

        WmsRuleDefinition definition = new WmsRuleDefinition();
        definition.setRuleKey(ruleKey);
        definition.setPurpose(purpose);
        definition.setRuleName(ruleName);
        definition.setScopeType(scopeType);
        definition.setStatus(WmsRuleContract.DEFINITION_STATUS_ACTIVE);
        definition.setRevision(1);
        try {
            definitionMapper.insert(definition);
        } catch (DuplicateKeyException error) {
            throw new ServiceException(
                WmsRuleContract.ERROR_RULE_KEY_EXISTS + ": 规则键已存在",
                HttpStatus.HTTP_CONFLICT
            );
        }

        WmsRuleVersion version = new WmsRuleVersion();
        version.setRuleDefinitionId(definition.getRuleDefinitionId());
        version.setVersionNo(1);
        version.setConditionJson(JsonUtils.toJsonString(condition));
        version.setResultJson(JsonUtils.toJsonString(result));
        version.setCanonicalDigest(digest);
        version.setStatus(WmsRuleContract.STATUS_DRAFT);
        versionMapper.insert(version);
        refreshVersionDigest(version.getRuleVersionId());
        WmsRuleVersion created = versionMapper.selectById(version.getRuleVersionId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ruleDefinitionId", definition.getRuleDefinitionId());
        after.put("ruleKey", ruleKey);
        after.put("purpose", purpose);
        after.put("scopeType", scopeType);
        after.put("status", WmsRuleContract.DEFINITION_STATUS_ACTIVE);
        after.put("revision", 1);
        after.put("ruleVersionId", created.getRuleVersionId());
        after.put("versionNo", created.getVersionNo());
        after.put("canonicalDigest", created.getCanonicalDigest());
        auditService.recordSuccess(
            WmsRuleContract.ACTION_DEFINITION_CREATE,
            WmsRuleContract.OBJECT_TYPE_DEFINITION,
            definition.getRuleDefinitionId(),
            command.getCommandKey(),
            command,
            null,
            after,
            command.getReason(),
            LocalDateTime.now(),
            WmsRuleContract.FACT_TYPE_DEFINITION,
            String.valueOf(definition.getRuleDefinitionId())
        );
        return loadDefinition(definition.getRuleDefinitionId());
    }

    @Override
    public WmsRuleVersionVo editDraft(WmsRuleDraftEditCommand command) {
        if (command == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        String commandKey = WmsRuleValidation.requireCommandKey(command.getCommandKey());
        Long ruleVersionId = WmsRuleValidation.requireId(command.getRuleVersionId(), "ruleVersionId");
        String expectedDigest = WmsRuleValidation.requireDigest(command.getExpectedDigest(), "expectedDigest");
        JsonNode condition = WmsRuleValidation.requireJson(command.getCondition(), "condition");
        JsonNode result = WmsRuleValidation.requireJson(command.getResult(), "result");
        return commandExecutor.execute(
            WmsRuleContract.ACTION_DRAFT_EDIT,
            WmsRuleContract.OBJECT_TYPE_VERSION,
            ruleVersionId,
            commandKey,
            command,
            Isolation.READ_COMMITTED,
            () -> doEditDraft(command, ruleVersionId, expectedDigest, condition, result),
            () -> bindingService.toVersionVo(versionMapper.selectById(ruleVersionId))
        );
    }

    private WmsRuleVersionVo doEditDraft(WmsRuleDraftEditCommand command, Long ruleVersionId,
                                         String expectedDigest, JsonNode condition, JsonNode result) {
        StpUtil.checkPermission(WmsRuleContract.PERMISSION_DRAFT);
        if (!auditService.assertReusable(command.getCommandKey(), auditService.digest(command),
            WmsRuleContract.ACTION_DRAFT_EDIT)) {
            return bindingService.toVersionVo(versionMapper.selectById(ruleVersionId));
        }
        WmsRuleVersion version = versionMapper.selectByIdForUpdate(ruleVersionId);
        if (version == null) {
            throw versionNotFound(ruleVersionId);
        }
        assertDraftMutable(version);
        if (!expectedDigest.equals(version.getCanonicalDigest())) {
            throw contentChanged("草稿内容已变化，请重新读取");
        }

        LocalDateTime occurredAt = LocalDateTime.now();
        int updated = versionMapper.update(
            null,
            new LambdaUpdateWrapper<WmsRuleVersion>()
                .eq(WmsRuleVersion::getRuleVersionId, ruleVersionId)
                .eq(WmsRuleVersion::getStatus, WmsRuleContract.STATUS_DRAFT)
                .eq(WmsRuleVersion::getCanonicalDigest, expectedDigest)
                .set(WmsRuleVersion::getAttributeBindingsJson, null)
                .set(WmsRuleVersion::getConditionJson, JsonUtils.toJsonString(condition))
                .set(WmsRuleVersion::getResultJson, JsonUtils.toJsonString(result))
                .set(WmsRuleVersion::getCanonicalDigest,
                    WmsRuleDigest.ruleVersionDigest(condition, result, null))
                .set(WmsRuleVersion::getUpdateBy, LoginHelper.getUserId())
                .set(WmsRuleVersion::getUpdateTime, occurredAt)
        );
        if (updated != 1) {
            WmsRuleVersion current = versionMapper.selectById(ruleVersionId);
            if (current != null) {
                assertDraftMutable(current);
            }
            throw contentChanged("草稿内容已被并发修改");
        }
        refreshVersionDigest(ruleVersionId);
        WmsRuleVersion current = versionMapper.selectById(ruleVersionId);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("ruleVersionId", ruleVersionId);
        before.put("canonicalDigest", expectedDigest);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ruleVersionId", ruleVersionId);
        after.put("canonicalDigest", current.getCanonicalDigest());
        after.put("versionNo", current.getVersionNo());
        after.put("status", current.getStatus());
        auditService.recordSuccess(
            WmsRuleContract.ACTION_DRAFT_EDIT,
            WmsRuleContract.OBJECT_TYPE_VERSION,
            ruleVersionId,
            command.getCommandKey(),
            command,
            before,
            after,
            command.getReason(),
            occurredAt,
            WmsRuleContract.FACT_TYPE_VERSION,
            String.valueOf(ruleVersionId)
        );
        return bindingService.toVersionVo(current);
    }

    @Override
    public WmsRuleVersionVo copyDraft(WmsRuleVersionCopyCommand command) {
        if (command == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        String commandKey = WmsRuleValidation.requireCommandKey(command.getCommandKey());
        Long sourceRuleVersionId =
            WmsRuleValidation.requireId(command.getSourceRuleVersionId(), "sourceRuleVersionId");
        return commandExecutor.execute(
            WmsRuleContract.ACTION_DRAFT_COPY,
            WmsRuleContract.OBJECT_TYPE_VERSION,
            sourceRuleVersionId,
            commandKey,
            command,
            Isolation.READ_COMMITTED,
            () -> doCopyDraft(command, sourceRuleVersionId),
            () -> replayVersion(commandKey)
        );
    }

    private WmsRuleVersionVo doCopyDraft(WmsRuleVersionCopyCommand command, Long sourceRuleVersionId) {
        StpUtil.checkPermission(WmsRuleContract.PERMISSION_DRAFT);
        if (!auditService.assertReusable(command.getCommandKey(), auditService.digest(command),
            WmsRuleContract.ACTION_DRAFT_COPY)) {
            return replayVersion(command.getCommandKey());
        }
        WmsRuleVersion source = versionMapper.selectByIdForUpdate(sourceRuleVersionId);
        if (source == null) {
            throw versionNotFound(sourceRuleVersionId);
        }
        if (!WmsRuleContract.STATUS_ACTIVE.equals(source.getStatus())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_VERSION_NOT_ACTIVE + ": 只能基于 ACTIVE 版本复制新草稿",
                HttpStatus.HTTP_CONFLICT
            );
        }
        WmsRuleDefinition definition = definitionMapper.selectByIdForUpdate(source.getRuleDefinitionId());
        if (definition == null) {
            throw definitionNotFound(source.getRuleDefinitionId());
        }
        assertDefinitionActive(definition);

        Integer maxVersionNo = versionMapper.selectMaxVersionNo(definition.getRuleDefinitionId());
        int nextVersionNo = (maxVersionNo == null ? 0 : maxVersionNo) + 1;
        WmsRuleVersion draft = new WmsRuleVersion();
        draft.setRuleDefinitionId(definition.getRuleDefinitionId());
        draft.setVersionNo(nextVersionNo);
        draft.setConditionJson(source.getConditionJson());
        draft.setResultJson(source.getResultJson());
        draft.setCanonicalDigest(source.getCanonicalDigest());
        draft.setStatus(WmsRuleContract.STATUS_DRAFT);
        try {
            versionMapper.insert(draft);
        } catch (DuplicateKeyException error) {
            throw new ServiceException(
                WmsRuleContract.ERROR_VERSION_NUMBER_CONFLICT + ": 规则版本号已被并发占用",
                HttpStatus.HTTP_CONFLICT
            );
        }
        refreshVersionDigest(draft.getRuleVersionId());
        WmsRuleVersion created = versionMapper.selectById(draft.getRuleVersionId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ruleVersionId", created.getRuleVersionId());
        after.put("sourceRuleVersionId", sourceRuleVersionId);
        after.put("versionNo", created.getVersionNo());
        after.put("status", created.getStatus());
        after.put("canonicalDigest", created.getCanonicalDigest());
        auditService.recordSuccess(
            WmsRuleContract.ACTION_DRAFT_COPY,
            WmsRuleContract.OBJECT_TYPE_VERSION,
            created.getRuleVersionId(),
            command.getCommandKey(),
            command,
            null,
            after,
            command.getReason(),
            LocalDateTime.now(),
            WmsRuleContract.FACT_TYPE_VERSION,
            String.valueOf(created.getRuleVersionId())
        );
        return bindingService.toVersionVo(created);
    }

    @Override
    public WmsRuleVersionVo activateVersion(WmsRuleVersionActivateCommand command) {
        if (command == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        String commandKey = WmsRuleValidation.requireCommandKey(command.getCommandKey());
        Long ruleVersionId = WmsRuleValidation.requireId(command.getRuleVersionId(), "ruleVersionId");
        String expectedDigest = WmsRuleValidation.requireDigest(command.getExpectedDigest(), "expectedDigest");
        return commandExecutor.execute(
            WmsRuleContract.ACTION_VERSION_ACTIVATE,
            WmsRuleContract.OBJECT_TYPE_VERSION,
            ruleVersionId,
            commandKey,
            command,
            Isolation.READ_COMMITTED,
            () -> doActivateVersion(command, ruleVersionId, expectedDigest),
            () -> bindingService.toVersionVo(versionMapper.selectById(ruleVersionId))
        );
    }

    private WmsRuleVersionVo doActivateVersion(WmsRuleVersionActivateCommand command, Long ruleVersionId,
                                               String expectedDigest) {
        StpUtil.checkPermission(WmsRuleContract.PERMISSION_PUBLISH);
        if (!auditService.assertReusable(command.getCommandKey(), auditService.digest(command),
            WmsRuleContract.ACTION_VERSION_ACTIVATE)) {
            return bindingService.toVersionVo(versionMapper.selectById(ruleVersionId));
        }
        WmsRuleVersion version = versionMapper.selectByIdForUpdate(ruleVersionId);
        if (version == null) {
            throw versionNotFound(ruleVersionId);
        }
        assertDraftMutable(version);
        if (!expectedDigest.equals(version.getCanonicalDigest())) {
            throw contentChanged("草稿内容已变化，请重新读取");
        }
        String recomputed = WmsRuleDigest.ruleVersionDigest(
            WmsRuleViewSupport.readJson(version.getConditionJson()),
            WmsRuleViewSupport.readJson(version.getResultJson()), null
        );
        boolean legacyDraftDigest = version.getAttributeBindingsJson() == null && WmsRuleDigest.ruleVersionDigest(
            WmsRuleViewSupport.readJson(version.getConditionJson()),
            WmsRuleViewSupport.readJson(version.getResultJson())).equals(version.getCanonicalDigest());
        if (version.getAttributeBindingsJson() != null || (!recomputed.equals(version.getCanonicalDigest()) && !legacyDraftDigest)) {
            throw new ServiceException(
                WmsRuleContract.ERROR_DIGEST_MISMATCH + ": 内容摘要与存储摘要不一致，拒绝激活",
                HttpStatus.HTTP_CONFLICT
            );
        }
        WmsRuleDefinition definition = definitionMapper.selectByIdForUpdate(version.getRuleDefinitionId());
        if (definition == null) {
            throw definitionNotFound(version.getRuleDefinitionId());
        }
        assertDefinitionActive(definition);
        WmsRuleBindingService.Compiled compiled = bindingService.compile(definition, version);

        LocalDateTime occurredAt = LocalDateTime.now();
        Long userId = LoginHelper.getUserId();
        int activated = versionMapper.update(
            null,
            new LambdaUpdateWrapper<WmsRuleVersion>()
                .eq(WmsRuleVersion::getRuleVersionId, ruleVersionId)
                .eq(WmsRuleVersion::getStatus, WmsRuleContract.STATUS_DRAFT)
                .eq(WmsRuleVersion::getCanonicalDigest, expectedDigest)
                .set(WmsRuleVersion::getAttributeBindingsJson, compiled.snapshotJson())
                .set(WmsRuleVersion::getCanonicalDigest, compiled.digest())
                .set(WmsRuleVersion::getStatus, WmsRuleContract.STATUS_ACTIVE)
                .set(WmsRuleVersion::getPublishedAt, occurredAt)
                .set(WmsRuleVersion::getPublishedBy, userId)
                .set(WmsRuleVersion::getUpdateBy, userId)
                .set(WmsRuleVersion::getUpdateTime, occurredAt)
        );
        if (activated != 1) {
            WmsRuleVersion current = versionMapper.selectById(ruleVersionId);
            if (current != null) {
                assertDraftMutable(current);
            }
            throw contentChanged("草稿状态已被并发修改");
        }

        int definitionUpdated = definitionMapper.update(
            null,
            new LambdaUpdateWrapper<WmsRuleDefinition>()
                .eq(WmsRuleDefinition::getRuleDefinitionId, definition.getRuleDefinitionId())
                .eq(WmsRuleDefinition::getRevision, definition.getRevision())
                .set(WmsRuleDefinition::getCurrentVersionId, ruleVersionId)
                .set(WmsRuleDefinition::getRevision, definition.getRevision() + 1)
                .set(WmsRuleDefinition::getUpdateBy, userId)
                .set(WmsRuleDefinition::getUpdateTime, occurredAt)
        );
        if (definitionUpdated != 1) {
            throw new ServiceException(
                WmsRuleContract.ERROR_VERSION_CONFLICT + ": 规则定义修订已变化",
                HttpStatus.HTTP_CONFLICT
            );
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", WmsRuleContract.STATUS_DRAFT);
        before.put("canonicalDigest", expectedDigest);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", WmsRuleContract.STATUS_ACTIVE);
        after.put("canonicalDigest", compiled.digest());
        after.put("ruleDefinitionId", definition.getRuleDefinitionId());
        after.put("currentVersionId", ruleVersionId);
        auditService.recordSuccess(
            WmsRuleContract.ACTION_VERSION_ACTIVATE,
            WmsRuleContract.OBJECT_TYPE_VERSION,
            ruleVersionId,
            command.getCommandKey(),
            command,
            before,
            after,
            command.getReason(),
            occurredAt,
            WmsRuleContract.FACT_TYPE_VERSION,
            String.valueOf(ruleVersionId)
        );
        WmsRuleVersion activatedVersion = versionMapper.selectById(ruleVersionId);
        bindingService.requireReady(activatedVersion, definition);
        return bindingService.toVersionVo(activatedVersion);
    }

    @Override
    public WmsRuleDefinitionVo retireDefinition(WmsRuleDefinitionRetireCommand command) {
        if (command == null) {
            throw WmsRuleValidation.invalid("请求体不能为空");
        }
        String commandKey = WmsRuleValidation.requireCommandKey(command.getCommandKey());
        Long ruleDefinitionId =
            WmsRuleValidation.requireId(command.getRuleDefinitionId(), "ruleDefinitionId");
        if (command.getExpectedRevision() == null || command.getExpectedRevision() < 1) {
            throw WmsRuleValidation.invalid("expectedRevision 必须为正整数");
        }
        return commandExecutor.execute(
            WmsRuleContract.ACTION_DEFINITION_RETIRE,
            WmsRuleContract.OBJECT_TYPE_DEFINITION,
            ruleDefinitionId,
            commandKey,
            command,
            Isolation.READ_COMMITTED,
            () -> doRetireDefinition(command, ruleDefinitionId),
            () -> loadDefinition(ruleDefinitionId)
        );
    }

    private WmsRuleDefinitionVo doRetireDefinition(WmsRuleDefinitionRetireCommand command,
                                                   Long ruleDefinitionId) {
        StpUtil.checkPermission(WmsRuleContract.PERMISSION_DRAFT);
        if (!auditService.assertReusable(command.getCommandKey(), auditService.digest(command),
            WmsRuleContract.ACTION_DEFINITION_RETIRE)) {
            return loadDefinition(ruleDefinitionId);
        }
        WmsRuleDefinition definition = definitionMapper.selectByIdForUpdate(ruleDefinitionId);
        if (definition == null) {
            throw definitionNotFound(ruleDefinitionId);
        }
        if (WmsRuleContract.DEFINITION_STATUS_DISABLED.equals(definition.getStatus())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_DEFINITION_RETIRED + ": 规则定义已停用",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (!command.getExpectedRevision().equals(definition.getRevision())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_VERSION_CONFLICT + ": 规则定义修订已变化",
                HttpStatus.HTTP_CONFLICT
            );
        }
        int nextRevision = definition.getRevision() + 1;
        int updated = definitionMapper.update(
            null,
            new LambdaUpdateWrapper<WmsRuleDefinition>()
                .eq(WmsRuleDefinition::getRuleDefinitionId, ruleDefinitionId)
                .eq(WmsRuleDefinition::getRevision, definition.getRevision())
                .set(WmsRuleDefinition::getStatus, WmsRuleContract.DEFINITION_STATUS_DISABLED)
                .set(WmsRuleDefinition::getRevision, nextRevision)
                .set(WmsRuleDefinition::getUpdateBy, LoginHelper.getUserId())
                .set(WmsRuleDefinition::getUpdateTime, LocalDateTime.now())
        );
        if (updated != 1) {
            throw new ServiceException(
                WmsRuleContract.ERROR_VERSION_CONFLICT + ": 规则定义修订已变化",
                HttpStatus.HTTP_CONFLICT
            );
        }
        Long activeVersions = versionMapper.selectCount(
            new LambdaQueryWrapper<WmsRuleVersion>()
                .eq(WmsRuleVersion::getRuleDefinitionId, ruleDefinitionId)
                .eq(WmsRuleVersion::getStatus, WmsRuleContract.STATUS_ACTIVE)
        );

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", WmsRuleContract.DEFINITION_STATUS_ACTIVE);
        before.put("revision", definition.getRevision());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", WmsRuleContract.DEFINITION_STATUS_DISABLED);
        after.put("revision", nextRevision);
        after.put("activeVersionCount", activeVersions == null ? 0L : activeVersions);
        after.put("retainActiveVersions", Boolean.TRUE);
        auditService.recordSuccess(
            WmsRuleContract.ACTION_DEFINITION_RETIRE,
            WmsRuleContract.OBJECT_TYPE_DEFINITION,
            ruleDefinitionId,
            command.getCommandKey(),
            command,
            before,
            after,
            command.getReason(),
            LocalDateTime.now(),
            WmsRuleContract.FACT_TYPE_DEFINITION,
            String.valueOf(ruleDefinitionId)
        );
        return loadDefinition(ruleDefinitionId);
    }

    private void refreshVersionDigest(Long ruleVersionId) {
        WmsRuleVersion stored = versionMapper.selectById(ruleVersionId);
        if (stored == null) {
            throw versionNotFound(ruleVersionId);
        }
        String recomputed = WmsRuleDigest.ruleVersionDigest(
            WmsRuleViewSupport.readJson(stored.getConditionJson()),
            WmsRuleViewSupport.readJson(stored.getResultJson()), null
        );
        if (!recomputed.equals(stored.getCanonicalDigest())) {
            versionMapper.update(
                null,
                new LambdaUpdateWrapper<WmsRuleVersion>()
                    .eq(WmsRuleVersion::getRuleVersionId, ruleVersionId)
                    .set(WmsRuleVersion::getCanonicalDigest, recomputed)
            );
        }
    }

    private WmsRuleDefinitionVo loadDefinition(Long ruleDefinitionId) {
        WmsRuleDefinition definition = definitionMapper.selectById(ruleDefinitionId);
        if (definition == null) {
            throw definitionNotFound(ruleDefinitionId);
        }
        List<WmsRuleVersionVo> versions = versionMapper.selectList(
            new LambdaQueryWrapper<WmsRuleVersion>()
                .eq(WmsRuleVersion::getRuleDefinitionId, ruleDefinitionId)
                .orderByAsc(WmsRuleVersion::getVersionNo)
        ).stream().map(bindingService::toVersionVo).toList();
        return WmsRuleViewSupport.toDefinitionVo(definition, versions);
    }

    private WmsRuleDefinitionVo replayDefinition(String commandKey) {
        WmsCommandAttempt attempt = auditService.findAttempt(commandKey);
        if (attempt == null || attempt.getFactId() == null) {
            throw new ServiceException(
                "COMMAND_REPLAY_UNAVAILABLE: 已生效命令缺少可读回事实",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return loadDefinition(Long.valueOf(attempt.getFactId()));
    }

    private WmsRuleVersionVo replayVersion(String commandKey) {
        WmsCommandAttempt attempt = auditService.findAttempt(commandKey);
        if (attempt == null || attempt.getFactId() == null) {
            throw new ServiceException(
                "COMMAND_REPLAY_UNAVAILABLE: 已生效命令缺少可读回事实",
                HttpStatus.HTTP_CONFLICT
            );
        }
        return bindingService.toVersionVo(versionMapper.selectById(Long.valueOf(attempt.getFactId())));
    }

    private void assertDraftMutable(WmsRuleVersion version) {
        if (WmsRuleContract.STATUS_DRAFT.equals(version.getStatus())) {
            return;
        }
        if (WmsRuleContract.STATUS_RETIRED.equals(version.getStatus())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_VERSION_RETIRED + ": 已退役版本只读",
                HttpStatus.HTTP_CONFLICT
            );
        }
        throw new ServiceException(
            WmsRuleContract.ERROR_VERSION_IMMUTABLE
                + ": 已发布版本内容不可原位修改，请基于旧版复制新草稿",
            HttpStatus.HTTP_CONFLICT
        );
    }

    private void assertDefinitionActive(WmsRuleDefinition definition) {
        if (!WmsRuleContract.DEFINITION_STATUS_ACTIVE.equals(definition.getStatus())) {
            throw new ServiceException(
                WmsRuleContract.ERROR_DEFINITION_NOT_ACTIVE + ": 规则定义已停用，不再接受新版本或新引用",
                HttpStatus.HTTP_CONFLICT
            );
        }
    }

    private ServiceException contentChanged(String message) {
        return new ServiceException(
            WmsRuleContract.ERROR_CONTENT_CHANGED + ": " + message,
            HttpStatus.HTTP_CONFLICT
        );
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
}
