/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划版本聚合保存、校验、发布与系统模式；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.endpoint.catalog.EndpointAddressResolution;
import com.innolux.iws.endpoint.catalog.EndpointAddressSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointCatalogConflictException;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemAddressSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionEdgeBo;
import com.innolux.iws.process.api.DefinitionEventBo;
import com.innolux.iws.process.api.DefinitionEventPatchBo;
import com.innolux.iws.process.api.DefinitionStateBo;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.api.EditCapabilitiesVo;
import com.innolux.iws.process.api.EventEditCapabilityVo;
import com.innolux.iws.process.api.ExternalSystemPostingOptionsVO;
import com.innolux.iws.process.api.ExternalSystemRowVO;
import com.innolux.iws.process.api.FieldCapabilityVo;
import com.innolux.iws.process.api.PostingImpactVO;
import com.innolux.iws.process.api.PostingModeVectorBo;
import com.innolux.iws.process.api.PostingPolicySaveResultVo;
import com.innolux.iws.process.api.SystemPolicyBo;
import com.innolux.iws.process.api.SystemModeDiffVo;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.enums.ActionSource;
import com.innolux.iws.process.enums.ActionType;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.CompletionContract;
import com.innolux.iws.process.enums.CompletionPolicy;
import com.innolux.iws.process.enums.DefinitionStatus;
import com.innolux.iws.process.enums.EditPolicy;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.GraphEditPolicy;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.SkipPolicy;
import com.innolux.iws.process.enums.StateKind;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEdgeMapper;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessStateMapper;
import com.innolux.iws.process.mapper.ProcessSystemPolicyMapper;
import com.innolux.iws.process.service.CommandReceiptService;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.service.ProcessAuditService;
import com.innolux.iws.process.support.DefinitionCommandSupport;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import com.innolux.iws.process.support.DefinitionPostingRules;
import com.innolux.iws.process.support.DefinitionReceiptStore;
import com.innolux.iws.process.support.DefinitionValidator;
import com.innolux.iws.process.support.EditCapabilityResolver;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 计划版本聚合服务实现。
 *
 * <p>写命令统一按“目录控制共享锁 → 定义行锁 → 命令回执 → expectedVersion → 子表”顺序执行；
 * 回执键显式包含操作者目标，公共 CommandReceiptService 的契约保持不变。</p>
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(name = "com.innolux.iws.endpoint.catalog.EndpointCatalogReader")
public class PlanDefinitionServiceImpl implements PlanDefinitionService {

    private static final String DELETED = "1";

    private static final List<String> BUSINESS_LOCKED_FIELDS = List.of("planCode", "planVersion", "planRole",
        "planType", "editPolicy", "graphEditPolicy", "lockedFields");

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessStateMapper stateMapper;

    private final ProcessEventDefinitionMapper eventMapper;

    private final ProcessEdgeMapper edgeMapper;

    private final ProcessSystemPolicyMapper policyMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final DefinitionValidator definitionValidator;

    private final DefinitionGraphBuilder graphBuilder;

    private final EndpointCatalogReader catalogReader;

    private final ProcessAuditService auditService;

    private final EditCapabilityResolver editCapabilityResolver;

    private final CommandReceiptService commandReceiptService;

    private final DefinitionReceiptStore receiptStore;

    /**
     * 构造服务。
     *
     * @param definitionMapper 定义 Mapper
     * @param stateMapper 状态 Mapper
     * @param eventMapper 事件 Mapper
     * @param edgeMapper 边 Mapper
     * @param policyMapper 系统模式 Mapper
     * @param instanceMapper 实例 Mapper
     * @param definitionValidator 发布校验器
     * @param graphBuilder 图构建器
     * @param catalogReader endpoint 目录 Reader
     * @param auditService 审计服务
     * @param editCapabilityResolver 编辑门禁
     * @param commandReceiptService 命令回执
     * @param receiptStore 回执当前读与版本序列
     */
    public PlanDefinitionServiceImpl(PlanDefinitionMapper definitionMapper, ProcessStateMapper stateMapper,
                                     ProcessEventDefinitionMapper eventMapper, ProcessEdgeMapper edgeMapper,
                                     ProcessSystemPolicyMapper policyMapper, ProcessInstanceMapper instanceMapper,
                                     DefinitionValidator definitionValidator, DefinitionGraphBuilder graphBuilder,
                                     EndpointCatalogReader catalogReader, ProcessAuditService auditService,
                                     EditCapabilityResolver editCapabilityResolver,
                                     CommandReceiptService commandReceiptService,
                                     DefinitionReceiptStore receiptStore) {
        this.definitionMapper = definitionMapper;
        this.stateMapper = stateMapper;
        this.eventMapper = eventMapper;
        this.edgeMapper = edgeMapper;
        this.policyMapper = policyMapper;
        this.instanceMapper = instanceMapper;
        this.definitionValidator = definitionValidator;
        this.graphBuilder = graphBuilder;
        this.catalogReader = catalogReader;
        this.auditService = auditService;
        this.editCapabilityResolver = editCapabilityResolver;
        this.commandReceiptService = commandReceiptService;
        this.receiptStore = receiptStore;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<DefinitionSummaryVo> page(String planCode, String status, String planRole,
                                                PageQuery pageQuery) {
        Page<PlanDefinition> page = definitionMapper.selectPage(pageQuery.build(),
            Wrappers.<PlanDefinition>lambdaQuery()
                .like(planCode != null && !planCode.isBlank(), PlanDefinition::getPlanCode, planCode)
                .eq(status != null && !status.isBlank(), PlanDefinition::getStatus, status)
                .eq(planRole != null && !planRole.isBlank(), PlanDefinition::getPlanRole, planRole)
                .orderByDesc(PlanDefinition::getPlanCode, PlanDefinition::getPlanVersion));
        return PageResult.build(page.getRecords().stream().map(this::summary).toList(), page.getTotal());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DefinitionDetailVo get(long versionId) {
        return detail(loadAggregate(versionId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionDetailVo createDraft(DefinitionDraftBo draft, String commandKey, String reason) {
        if (draft == null) {
            throw new ServiceException("草稿不能为空");
        }
        String planCode = ProcessText.required(draft.planCode(), "计划编码");
        String target = DefinitionCommandSupport.target("definition-new", planCode);
        String payload = DefinitionCommandSupport.payloadHash(draft);
        DefinitionDetailVo replay = replayDetail(ActionType.DEFINITION_CREATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        receiptStore.lockPlan(planCode);
        replay = replayDetailCurrent(ActionType.DEFINITION_CREATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        PlanDefinition existing = definitionMapper.selectOne(Wrappers.<PlanDefinition>lambdaQuery()
            .eq(PlanDefinition::getPlanCode, planCode)
            .eq(PlanDefinition::getLatestKey, planCode));
        if (existing != null) {
            throw new ServiceException("已存在最新草稿: " + existing.getId());
        }
        int maxVersion = receiptStore.maxPlanVersion(planCode);
        int version = draft.planVersion() == null ? maxVersion + 1 : draft.planVersion();
        if (draft.planVersion() != null && version != maxVersion + 1) {
            throw new ServiceException("新草稿业务版本必须为 " + (maxVersion + 1) + "，历史最大版本 " + maxVersion);
        }
        PlanDefinition definition = new PlanDefinition();
        definition.setPlanCode(planCode);
        definition.setPlanVersion(version);
        definition.setPlanName(ProcessText.required(draft.planName(), "计划名称"));
        applyHeader(definition, draft);
        definition.setStatus(DefinitionStatus.DRAFT);
        definition.setRevision(1L);
        definition.setLatestKey(planCode);
        definition.setDelFlag("0");
        definition.setPublishedSummary("初始草稿");
        try {
            definitionMapper.insert(definition);
        } catch (DuplicateKeyException ex) {
            throw new ServiceException("已存在最新草稿或业务版本冲突，请重新读取");
        }
        replaceChildren(definition.getId(), draft.states(), draft.events(), draft.edges(), draft.systemPolicies());
        appendAudit(ActionType.DEFINITION_CREATE, target, definition.getId(), definition.getRevision(),
            commandKey, reason, payload, encodeDetail(definition.getId(), definition.getRevision(),
                definition.getStatus()));
        return get(definition.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionDetailVo saveDraft(long versionId, DefinitionDraftBo draft, long expectedVersion,
                                        String commandKey, String reason) {
        if (draft == null) {
            throw new ServiceException("草稿不能为空");
        }
        if (draft.expectedVersion() != null && draft.expectedVersion() != expectedVersion) {
            throw new ServiceException("请求体与信封的 expectedVersion 不一致");
        }
        String target = commandTarget("definition", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of(expectedVersion, draft));
        DefinitionDetailVo replay = replayDetail(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        lockCatalog(draft.catalogRevision());
        PlanDefinition definition = lockRow(versionId);
        replay = replayDetailCurrent(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        requireEditable(definition, expectedVersion);
        editCapabilityResolver.checkBase(definition, draft);
        DefinitionAggregate current = loadAggregate(versionId);
        editCapabilityResolver.checkChildren(current.states(), current.events(), draft.states(), draft.events());
        applyHeader(definition, draft);
        definition.setRevision(expectedVersion + 1);
        updateDefinition(definition, expectedVersion);
        replaceChildren(versionId, draft.states(), draft.events(), draft.edges(), draft.systemPolicies());
        ensureStructureValid(versionId);
        appendAudit(ActionType.DEFINITION_UPDATE, target, versionId, definition.getRevision(), commandKey, reason,
            payload, encodeDetail(versionId, definition.getRevision(), definition.getStatus()));
        return get(versionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionDetailVo saveBase(long versionId, DefinitionDraftBo draft, long expectedVersion,
                                       String commandKey, String reason) {
        if (draft == null) {
            throw new ServiceException("草稿不能为空");
        }
        if (draft.expectedVersion() != null && draft.expectedVersion() != expectedVersion) {
            throw new ServiceException("请求体与信封的 expectedVersion 不一致");
        }
        String target = commandTarget("definition", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of("base", expectedVersion, draft));
        DefinitionDetailVo replay = replayDetail(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        PlanDefinition definition = lockRow(versionId);
        replay = replayDetailCurrent(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        requireEditable(definition, expectedVersion);
        editCapabilityResolver.checkBase(definition, draft);
        applyHeader(definition, draft);
        definition.setRevision(expectedVersion + 1);
        updateDefinition(definition, expectedVersion);
        appendAudit(ActionType.DEFINITION_UPDATE, target, versionId, definition.getRevision(), commandKey, reason,
            payload, encodeDetail(versionId, definition.getRevision(), definition.getStatus()));
        return get(versionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionDetailVo saveState(long versionId, DefinitionStateBo state, long expectedVersion,
                                        String commandKey, String reason) {
        if (state == null) {
            throw new ServiceException("状态不能为空");
        }
        ProcessText.required(state.stateCode(), "状态编码");
        String target = commandTarget("definition", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of("state", expectedVersion, state));
        DefinitionDetailVo replay = replayDetail(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        PlanDefinition definition = lockRow(versionId);
        replay = replayDetailCurrent(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        requireEditable(definition, expectedVersion);
        editCapabilityResolver.checkState(currentState(versionId, state.stateCode()), state);
        stateMapper.delete(Wrappers.<ProcessState>lambdaQuery()
            .eq(ProcessState::getDefinitionVersionId, versionId)
            .eq(ProcessState::getStateCode, state.stateCode()));
        stateMapper.insert(toState(versionId, state));
        ensureStructureValid(versionId);
        definition.setRevision(expectedVersion + 1);
        updateDefinition(definition, expectedVersion);
        appendAudit(ActionType.DEFINITION_UPDATE, target, versionId, definition.getRevision(), commandKey, reason,
            payload, encodeDetail(versionId, definition.getRevision(), definition.getStatus()));
        return get(versionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionDetailVo saveEvent(long versionId, DefinitionEventPatchBo patch, String commandKey,
                                        String reason) {
        if (patch == null || patch.event() == null || patch.event().eventCode() == null
            || patch.event().eventCode().isBlank()) {
            throw new ServiceException("事件编码不能为空");
        }
        long expectedVersion = patch.expectedVersion() == null ? -1 : patch.expectedVersion();
        String target = commandTarget("definition", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of("event", expectedVersion, patch));
        DefinitionDetailVo replay = replayDetail(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        lockCatalog(patch.catalogRevision());
        PlanDefinition definition = lockRow(versionId);
        replay = replayDetailCurrent(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        requireEditable(definition, expectedVersion);
        DefinitionEventBo event = patch.event();
        editCapabilityResolver.checkEvent(currentEvent(versionId, event.eventCode()), event);
        eventMapper.delete(Wrappers.<ProcessEventDefinition>lambdaQuery()
            .eq(ProcessEventDefinition::getDefinitionVersionId, versionId)
            .eq(ProcessEventDefinition::getEventCode, event.eventCode()));
        eventMapper.insert(toEvent(versionId, event));
        edgeMapper.delete(Wrappers.<ProcessEdge>lambdaQuery()
            .eq(ProcessEdge::getDefinitionVersionId, versionId)
            .and(wrapper -> wrapper.eq(ProcessEdge::getPredecessorEventCode, event.eventCode())
                .or().eq(ProcessEdge::getSuccessorEventCode, event.eventCode())));
        if (patch.edges() != null) {
            for (DefinitionEdgeBo edge : patch.edges()) {
                edgeMapper.insert(toEdge(versionId, edge));
            }
        }
        ensureStructureValid(versionId);
        definition.setRevision(expectedVersion + 1);
        updateDefinition(definition, expectedVersion);
        appendAudit(ActionType.DEFINITION_UPDATE, target, versionId, definition.getRevision(), commandKey, reason,
            payload, encodeDetail(versionId, definition.getRevision(), definition.getStatus()));
        return get(versionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionDetailVo deleteEvent(long versionId, String eventCode, long expectedVersion,
                                          String commandKey, String reason) {
        ProcessText.required(eventCode, "事件编码");
        String target = commandTarget("definition", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of("delete-event", expectedVersion, eventCode));
        DefinitionDetailVo replay = replayDetail(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        PlanDefinition definition = lockRow(versionId);
        replay = replayDetailCurrent(ActionType.DEFINITION_UPDATE, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        requireEditable(definition, expectedVersion);
        ProcessEventDefinition existing = currentEvent(versionId, eventCode);
        if (existing == null) {
            throw new ServiceException("事件不存在: " + eventCode);
        }
        editCapabilityResolver.checkEventDelete(existing);
        eventMapper.delete(Wrappers.<ProcessEventDefinition>lambdaQuery()
            .eq(ProcessEventDefinition::getDefinitionVersionId, versionId)
            .eq(ProcessEventDefinition::getEventCode, eventCode));
        edgeMapper.delete(Wrappers.<ProcessEdge>lambdaQuery()
            .eq(ProcessEdge::getDefinitionVersionId, versionId)
            .and(wrapper -> wrapper.eq(ProcessEdge::getPredecessorEventCode, eventCode)
                .or().eq(ProcessEdge::getSuccessorEventCode, eventCode)));
        ensureStructureValid(versionId);
        definition.setRevision(expectedVersion + 1);
        updateDefinition(definition, expectedVersion);
        appendAudit(ActionType.DEFINITION_UPDATE, target, versionId, definition.getRevision(), commandKey, reason,
            payload, encodeDetail(versionId, definition.getRevision(), definition.getStatus()));
        return get(versionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PostingPolicySaveResultVo saveSystemPolicies(long versionId, PostingModeVectorBo vector) {
        if (vector == null) {
            throw new ServiceException("系统模式向量不能为空");
        }
        long expectedVersion = vector.expectedVersion() == null ? -1 : vector.expectedVersion();
        String target = commandTarget("definition-policy", versionId);
        String payload = DefinitionCommandSupport.payloadHash(vector);
        PostingPolicySaveResultVo replay = replayPolicies(ActionType.DEFINITION_UPDATE, target,
            vector.commandKey(), payload);
        if (replay != null) {
            return replay;
        }
        EndpointCatalogSnapshot catalog = lockCatalog(vector.catalogRevision());
        PlanDefinition definition = lockRow(versionId);
        replay = replayPoliciesCurrent(ActionType.DEFINITION_UPDATE, target, vector.commandKey(), payload);
        if (replay != null) {
            return replay;
        }
        requireEditable(definition, expectedVersion);
        String environmentCode = requireCurrentEnvironment(catalog);
        DefinitionAggregate aggregate = loadAggregate(versionId);
        PolicyEvaluation evaluation = evaluatePolicies(definition, aggregate, vector, catalog, environmentCode);
        if (!evaluation.impact().valid()) {
            throw new ServiceException("系统模式保存校验未通过: "
                + String.join("; ", evaluation.impact().errors()));
        }
        policyMapper.delete(Wrappers.<ProcessSystemPolicy>lambdaQuery()
            .eq(ProcessSystemPolicy::getDefinitionVersionId, versionId));
        for (SystemPolicyBo policy : vector.policies()) {
            policyMapper.insert(toPolicy(versionId, policy));
        }
        ensureStructureValid(versionId);
        definition.setRevision(expectedVersion + 1);
        updateDefinition(definition, expectedVersion);
        String result = encodePolicyResult(versionId, definition.getRevision(), catalog.catalogRevision(),
            environmentCode, evaluation.impact().bypassSystems());
        appendAudit(ActionType.DEFINITION_UPDATE, target, versionId, definition.getRevision(),
            vector.commandKey(), vector.reason(), payload, result);
        return new PostingPolicySaveResultVo(true, ProcessText.id(definition.getRevision()),
            catalog.catalogRevision(), environmentCode, List.copyOf(vector.policies()),
            evaluation.impact().bypassSystems(), evaluation.impact().affectedEvents(),
            evaluation.impact().unaffectedEvents(), List.of(), evaluation.impact().modeDiffs());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DefinitionValidationVo validate(long versionId) {
        DefinitionAggregate aggregate = loadAggregate(versionId);
        return definitionValidator.validate(aggregate.definition(), aggregate.states(), aggregate.events(),
            aggregate.edges(), aggregate.policies());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExternalSystemPostingOptionsVO externalSystems(long versionId) {
        DefinitionAggregate aggregate = loadAggregate(versionId);
        EndpointCatalogSnapshot catalog = catalogReader.snapshot();
        String environmentCode = requireCurrentEnvironment(catalog);
        EndpointEnvironmentSnapshot environment = catalog.environments().stream()
            .filter(item -> item.environmentCode().equals(environmentCode)).findFirst().orElse(null);
        PlanDefinition definition = aggregate.definition();
        boolean editable = isEditableBusinessDraft(definition);
        Map<String, PostingMode> modes = new LinkedHashMap<>();
        for (ProcessSystemPolicy policy : aggregate.policies()) {
            if (policy.getPostingMode() != null) {
                modes.put(policy.getSystemCode(), policy.getPostingMode());
            }
        }
        List<String> policyCodes = DefinitionPostingRules.policySystems(aggregate.events());
        Map<String, List<ProcessEventDefinition>> callerEvents = callerEvents(aggregate.events());
        Set<String> display = new LinkedHashSet<>(policyCodes);
        for (ProcessEventDefinition event : aggregate.events()) {
            if (event.getTargetSystemCode() != null && !event.getTargetSystemCode().isBlank()) {
                display.add(event.getTargetSystemCode());
            }
        }
        display.addAll(callerEvents.keySet());
        List<String> orderedDisplay = new ArrayList<>(display);
        orderedDisplay.sort(String::compareTo);
        List<ExternalSystemRowVO> rows = new ArrayList<>();
        for (String systemCode : orderedDisplay) {
            rows.add(externalSystemRow(definition, aggregate.events(), systemCode, policyCodes.contains(systemCode),
                editable, modes.get(systemCode), catalog, environmentCode,
                callerEvents.getOrDefault(systemCode, List.of())));
        }
        List<ExternalSystemRowVO> policyRows = rows.stream()
            .filter(ExternalSystemRowVO::policyOwner).toList();
        return new ExternalSystemPostingOptionsVO(ProcessText.id(definition.getRevision()),
            catalog.catalogRevision(), environmentCode,
            environment == null ? null : environment.revision(), policyRows, rows, LocalDateTime.now());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PostingImpactVO previewSystemPolicies(long versionId, PostingModeVectorBo vector) {
        if (vector == null) {
            throw new ServiceException("系统模式向量不能为空");
        }
        DefinitionAggregate aggregate = loadAggregate(versionId);
        EndpointCatalogSnapshot catalog = catalogReader.snapshot();
        String environmentCode = requireCurrentEnvironment(catalog);
        return evaluatePolicies(aggregate.definition(), aggregate, vector, catalog, environmentCode)
            .impact();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionValidationVo publish(long versionId, long expectedVersion, String commandKey, String reason) {
        String target = commandTarget("definition-publish", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of("publish", expectedVersion));
        DefinitionValidationVo replay = replayPublish(ActionType.DEFINITION_PUBLISH, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        EndpointCatalogSnapshot catalog = lockCurrentCatalog();
        PlanDefinition definition = lockRow(versionId);
        replay = replayPublishCurrent(ActionType.DEFINITION_PUBLISH, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        requireEditable(definition, expectedVersion);
        DefinitionAggregate aggregate = loadAggregate(versionId);
        DefinitionValidationVo validation = definitionValidator.validate(aggregate.definition(),
            aggregate.states(), aggregate.events(), aggregate.edges(), aggregate.policies());
        if (!validation.valid()) {
            throw new ServiceException("发布校验未通过: " + String.join("; ", validation.errors()));
        }
        String environmentCode = requireCurrentEnvironment(catalog);
        List<String> errors = validatePublishCatalog(aggregate, catalog, environmentCode);
        if (!errors.isEmpty()) {
            throw new ServiceException("发布目录校验未通过: " + String.join("; ", errors));
        }
        definitionMapper.update(null, Wrappers.<PlanDefinition>lambdaUpdate()
            .eq(PlanDefinition::getPlanCode, definition.getPlanCode())
            .eq(PlanDefinition::getStatus, DefinitionStatus.ACTIVE)
            .set(PlanDefinition::getStatus, DefinitionStatus.RETIRED)
            .set(PlanDefinition::getActiveKey, null)
            .set(PlanDefinition::getUpdateTime, LocalDateTime.now()));
        definition.setStatus(DefinitionStatus.ACTIVE);
        definition.setActiveKey(definition.getPlanCode());
        definition.setLatestKey(null);
        definition.setRevision(expectedVersion + 1);
        definition.setPublishedAt(LocalDateTime.now());
        definition.setPublishedBy(DefinitionCommandSupport.actorUserId());
        definition.setPublishedSummary("发布修订 " + definition.getRevision() + " 摘要 "
            + contentDigest(aggregate));
        updateDefinition(definition, expectedVersion);
        DefinitionValidationVo result = new DefinitionValidationVo(true, ProcessText.id(versionId),
            ProcessText.id(definition.getRevision()), List.of(), validation.warnings());
        appendAudit(ActionType.DEFINITION_PUBLISH, target, versionId, definition.getRevision(), commandKey, reason,
            payload, encodePublish(result));
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DefinitionDetailVo copy(long versionId, String commandKey, String reason) {
        String target = commandTarget("definition-copy", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of("copy", versionId));
        DefinitionDetailVo replay = replayDetail(ActionType.DEFINITION_COPY, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        PlanDefinition sourceDefinition = requireDefinition(versionId);
        receiptStore.lockPlan(sourceDefinition.getPlanCode());
        replay = replayDetailCurrent(ActionType.DEFINITION_COPY, target, commandKey, payload);
        if (replay != null) {
            return replay;
        }
        DefinitionAggregate source = loadAggregate(versionId);
        PlanDefinition latest = definitionMapper.selectOne(Wrappers.<PlanDefinition>lambdaQuery()
            .eq(PlanDefinition::getPlanCode, source.definition().getPlanCode())
            .eq(PlanDefinition::getLatestKey, source.definition().getPlanCode()));
        if (latest != null) {
            throw new ServiceException("已存在最新草稿: " + latest.getId());
        }
        int maxVersion = receiptStore.maxPlanVersion(source.definition().getPlanCode());
        PlanDefinition copy = new PlanDefinition();
        copy.setPlanCode(source.definition().getPlanCode());
        copy.setPlanVersion(maxVersion + 1);
        copy.setPlanType(source.definition().getPlanType());
        copy.setBusinessType(source.definition().getBusinessType());
        copy.setBusinessScheduleCode(source.definition().getBusinessScheduleCode());
        copy.setPlanName(source.definition().getPlanName());
        copy.setPlanRole(source.definition().getPlanRole());
        copy.setStatus(DefinitionStatus.DRAFT);
        copy.setRevision(1L);
        copy.setLatestKey(copy.getPlanCode());
        copy.setActiveKey(null);
        copy.setSelfSystemCode(source.definition().getSelfSystemCode());
        copy.setMode(source.definition().getMode());
        copy.setActivePlanLimit(source.definition().getActivePlanLimit());
        copy.setFairnessWindowSize(source.definition().getFairnessWindowSize());
        copy.setMaxExecutionMs(source.definition().getMaxExecutionMs());
        copy.setCompletionPolicy(source.definition().getCompletionPolicy());
        copy.setExceptionStateCode(source.definition().getExceptionStateCode());
        copy.setRoundBatchLimit(source.definition().getRoundBatchLimit());
        copy.setRoundMaxDurationMs(source.definition().getRoundMaxDurationMs());
        copy.setScheduleIntervalMs(source.definition().getScheduleIntervalMs());
        copy.setScheduleCron(source.definition().getScheduleCron());
        copy.setJobTimeoutMs(source.definition().getJobTimeoutMs());
        copy.setJobMaxRetryTimes(source.definition().getJobMaxRetryTimes());
        copy.setWorkerBatchLimit(source.definition().getWorkerBatchLimit());
        copy.setWorkerMaxDurationMs(source.definition().getWorkerMaxDurationMs());
        copy.setLeaseMs(source.definition().getLeaseMs());
        copy.setRetryMax(source.definition().getRetryMax());
        copy.setRetryBackoffMs(source.definition().getRetryBackoffMs());
        copy.setDescription(source.definition().getDescription());
        copy.setDelFlag("0");
        copy.setPublishedSummary("复制自版本 " + source.definition().getPlanVersion());
        try {
            definitionMapper.insert(copy);
        } catch (DuplicateKeyException ex) {
            throw new ServiceException("并发复制冲突：已存在最新草稿，请重新读取");
        }
        for (ProcessState state : source.states()) {
            ProcessState item = copyState(state);
            item.setDefinitionVersionId(copy.getId());
            stateMapper.insert(item);
        }
        for (ProcessEventDefinition event : source.events()) {
            ProcessEventDefinition item = copyEvent(event);
            item.setDefinitionVersionId(copy.getId());
            eventMapper.insert(item);
        }
        for (ProcessEdge edge : source.edges()) {
            ProcessEdge item = copyEdge(edge);
            item.setDefinitionVersionId(copy.getId());
            edgeMapper.insert(item);
        }
        for (ProcessSystemPolicy policy : source.policies()) {
            ProcessSystemPolicy item = copyPolicy(policy);
            item.setDefinitionVersionId(copy.getId());
            policyMapper.insert(item);
        }
        appendAudit(ActionType.DEFINITION_COPY, target, copy.getId(), copy.getRevision(), commandKey, reason,
            payload, encodeDetail(copy.getId(), copy.getRevision(), copy.getStatus()));
        return get(copy.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void delete(long versionId, long expectedVersion, String commandKey, String reason) {
        String target = commandTarget("definition-delete", versionId);
        String payload = DefinitionCommandSupport.payloadHash(List.of("delete", expectedVersion));
        if (replayDelete(ActionType.DEFINITION_DELETE, target, commandKey, payload)) {
            return;
        }
        PlanDefinition definition = lockRow(versionId);
        if (replayDeleteCurrent(ActionType.DEFINITION_DELETE, target, commandKey, payload)) {
            return;
        }
        requireEditable(definition, expectedVersion);
        long references = instanceMapper.selectCount(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getDefinitionVersionId, versionId));
        if (references > 0) {
            throw new ServiceException("草稿已被计划实例引用，不能删除");
        }
        definition.setDelFlag(DELETED);
        definition.setLatestKey(null);
        definition.setRevision(expectedVersion + 1);
        updateDefinition(definition, expectedVersion);
        appendAudit(ActionType.DEFINITION_DELETE, target, versionId, definition.getRevision(), commandKey, reason,
            payload, "X|" + versionId + "|" + definition.getRevision());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EditCapabilitiesVo editCapabilities(long versionId) {
        PlanDefinition definition = requireDefinition(versionId);
        boolean editable = isLatestDraft(definition);
        DefinitionAggregate aggregate = loadAggregate(versionId);
        List<EventEditCapabilityVo> events = aggregate.events().stream()
            .map(item -> eventCapability(item, editable)).toList();
        return new EditCapabilitiesVo(ProcessText.id(versionId), ProcessText.id(definition.getRevision()), editable,
            editable ? null : "仅最新草稿可编辑", BUSINESS_LOCKED_FIELDS,
            fieldCapabilities(definition, editable), events);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SystemPolicyBo> systemPolicies(long versionId) {
        return loadAggregate(versionId).policies().stream()
            .map(item -> new SystemPolicyBo(item.getSystemCode(), item.getPostingMode() == null ? null
                : item.getPostingMode().name(), item.getFirstConfirmPolicy(), item.getFailurePolicy(),
                item.getRemark()))
            .toList();
    }

    private PlanDefinition lockRow(long versionId) {
        PlanDefinition definition = definitionMapper.selectOne(Wrappers.<PlanDefinition>lambdaQuery()
            .eq(PlanDefinition::getId, versionId)
            .last("FOR UPDATE"));
        if (definition == null) {
            throw new ServiceException("计划版本不存在: " + versionId);
        }
        return definition;
    }

    private void requireEditable(PlanDefinition definition, long expectedVersion) {
        if (definition.getStatus() != DefinitionStatus.DRAFT
            || !definition.getPlanCode().equals(definition.getLatestKey())) {
            throw new ServiceException("版本只读: 仅最新草稿可编辑");
        }
        if (definition.getRevision() == null || !definition.getRevision().equals(expectedVersion)) {
            throw new ServiceException("版本冲突，当前 revision=" + definition.getRevision());
        }
    }

    private boolean isEditableBusinessDraft(PlanDefinition definition) {
        return isLatestDraft(definition) && definition.getPlanRole() == PlanRole.BUSINESS;
    }

    private boolean isLatestDraft(PlanDefinition definition) {
        return definition.getStatus() == DefinitionStatus.DRAFT
            && definition.getPlanCode().equals(definition.getLatestKey());
    }

    private PlanDefinition requireDefinition(long versionId) {
        PlanDefinition definition = definitionMapper.selectById(versionId);
        if (definition == null) {
            throw new ServiceException("计划版本不存在: " + versionId);
        }
        return definition;
    }

    private void updateDefinition(PlanDefinition definition, long expectedVersion) {
        int rows = definitionMapper.update(null, Wrappers.<PlanDefinition>lambdaUpdate()
            .eq(PlanDefinition::getId, definition.getId())
            .eq(PlanDefinition::getRevision, expectedVersion)
            .set(PlanDefinition::getPlanName, definition.getPlanName())
            .set(PlanDefinition::getPlanType, definition.getPlanType())
            .set(PlanDefinition::getBusinessType, definition.getBusinessType())
            .set(PlanDefinition::getBusinessScheduleCode, definition.getBusinessScheduleCode())
            .set(PlanDefinition::getPlanRole, definition.getPlanRole())
            .set(PlanDefinition::getStatus, definition.getStatus())
            .set(PlanDefinition::getLatestKey, definition.getLatestKey())
            .set(PlanDefinition::getActiveKey, definition.getActiveKey())
            .set(PlanDefinition::getSelfSystemCode, definition.getSelfSystemCode())
            .set(PlanDefinition::getMode, definition.getMode())
            .set(PlanDefinition::getActivePlanLimit, definition.getActivePlanLimit())
            .set(PlanDefinition::getFairnessWindowSize, definition.getFairnessWindowSize())
            .set(PlanDefinition::getMaxExecutionMs, definition.getMaxExecutionMs())
            .set(PlanDefinition::getCompletionPolicy, definition.getCompletionPolicy())
            .set(PlanDefinition::getExceptionStateCode, definition.getExceptionStateCode())
            .set(PlanDefinition::getRoundBatchLimit, definition.getRoundBatchLimit())
            .set(PlanDefinition::getRoundMaxDurationMs, definition.getRoundMaxDurationMs())
            .set(PlanDefinition::getScheduleIntervalMs, definition.getScheduleIntervalMs())
            .set(PlanDefinition::getScheduleCron, definition.getScheduleCron())
            .set(PlanDefinition::getJobTimeoutMs, definition.getJobTimeoutMs())
            .set(PlanDefinition::getJobMaxRetryTimes, definition.getJobMaxRetryTimes())
            .set(PlanDefinition::getWorkerBatchLimit, definition.getWorkerBatchLimit())
            .set(PlanDefinition::getWorkerMaxDurationMs, definition.getWorkerMaxDurationMs())
            .set(PlanDefinition::getLeaseMs, definition.getLeaseMs())
            .set(PlanDefinition::getRetryMax, definition.getRetryMax())
            .set(PlanDefinition::getRetryBackoffMs, definition.getRetryBackoffMs())
            .set(PlanDefinition::getDescription, definition.getDescription())
            .set(PlanDefinition::getPublishedBy, definition.getPublishedBy())
            .set(PlanDefinition::getPublishedAt, definition.getPublishedAt())
            .set(PlanDefinition::getPublishedSummary, definition.getPublishedSummary())
            .set(PlanDefinition::getDelFlag, definition.getDelFlag())
            .set(PlanDefinition::getRevision, definition.getRevision())
            .set(PlanDefinition::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new ServiceException("版本已变化，请重新读取");
        }
    }

    private void applyHeader(PlanDefinition definition, DefinitionDraftBo draft) {
        if (draft.planName() != null) {
            definition.setPlanName(draft.planName());
        }
        if (draft.planType() != null) {
            definition.setPlanType(draft.planType());
        }
        if (draft.businessType() != null) {
            definition.setBusinessType(draft.businessType());
        }
        if (draft.businessScheduleCode() != null) {
            definition.setBusinessScheduleCode(draft.businessScheduleCode());
        }
        if (draft.selfSystemCode() != null) {
            definition.setSelfSystemCode(draft.selfSystemCode());
        }
        if (draft.planRole() != null) {
            definition.setPlanRole(parse(PlanRole.class, draft.planRole(), "计划角色"));
        }
        if (draft.mode() != null && !draft.mode().isBlank()) {
            definition.setMode(parse(PlanMode.class, draft.mode(), "监督模式"));
        }
        if (draft.activePlanLimit() != null) {
            definition.setActivePlanLimit(draft.activePlanLimit());
        }
        if (draft.fairnessWindowSize() != null) {
            definition.setFairnessWindowSize(draft.fairnessWindowSize());
        }
        if (draft.maxExecutionMs() != null) {
            definition.setMaxExecutionMs(draft.maxExecutionMs());
        }
        if (draft.completionPolicy() != null) {
            definition.setCompletionPolicy(parse(CompletionPolicy.class, draft.completionPolicy(), "完成策略"));
        } else if (definition.getCompletionPolicy() == null) {
            definition.setCompletionPolicy(CompletionPolicy.ALL_SELECTED);
        }
        if (draft.exceptionStateCode() != null) {
            definition.setExceptionStateCode(draft.exceptionStateCode());
        }
        if (draft.roundBatchLimit() != null) {
            definition.setRoundBatchLimit(draft.roundBatchLimit());
        }
        if (draft.roundMaxDurationMs() != null) {
            definition.setRoundMaxDurationMs(draft.roundMaxDurationMs());
        }
        if (draft.scheduleIntervalMs() != null) {
            definition.setScheduleIntervalMs(draft.scheduleIntervalMs());
        }
        if (draft.scheduleCron() != null) {
            definition.setScheduleCron(draft.scheduleCron());
        }
        if (draft.jobTimeoutMs() != null) {
            definition.setJobTimeoutMs(draft.jobTimeoutMs());
        }
        if (draft.jobMaxRetryTimes() != null) {
            definition.setJobMaxRetryTimes(draft.jobMaxRetryTimes());
        }
        if (draft.workerBatchLimit() != null) {
            definition.setWorkerBatchLimit(draft.workerBatchLimit());
        }
        if (draft.workerMaxDurationMs() != null) {
            definition.setWorkerMaxDurationMs(draft.workerMaxDurationMs());
        }
        if (draft.leaseMs() != null) {
            definition.setLeaseMs(draft.leaseMs());
        }
        if (draft.retryMax() != null) {
            definition.setRetryMax(draft.retryMax());
        }
        if (draft.retryBackoffMs() != null) {
            definition.setRetryBackoffMs(draft.retryBackoffMs());
        }
        if (draft.description() != null) {
            definition.setDescription(draft.description());
        }
    }

    private void replaceChildren(long versionId, List<DefinitionStateBo> states, List<DefinitionEventBo> events,
                                 List<DefinitionEdgeBo> edges, List<SystemPolicyBo> policies) {
        stateMapper.delete(Wrappers.<ProcessState>lambdaQuery().eq(ProcessState::getDefinitionVersionId, versionId));
        eventMapper.delete(Wrappers.<ProcessEventDefinition>lambdaQuery()
            .eq(ProcessEventDefinition::getDefinitionVersionId, versionId));
        edgeMapper.delete(Wrappers.<ProcessEdge>lambdaQuery().eq(ProcessEdge::getDefinitionVersionId, versionId));
        policyMapper.delete(Wrappers.<ProcessSystemPolicy>lambdaQuery()
            .eq(ProcessSystemPolicy::getDefinitionVersionId, versionId));
        if (states != null) {
            for (DefinitionStateBo item : states) {
                stateMapper.insert(toState(versionId, item));
            }
        }
        if (events != null) {
            for (DefinitionEventBo item : events) {
                eventMapper.insert(toEvent(versionId, item));
            }
        }
        if (edges != null) {
            for (DefinitionEdgeBo item : edges) {
                edgeMapper.insert(toEdge(versionId, item));
            }
        }
        if (policies != null) {
            for (SystemPolicyBo item : policies) {
                policyMapper.insert(toPolicy(versionId, item));
            }
        }
    }

    private ProcessState toState(long versionId, DefinitionStateBo bo) {
        ProcessState state = new ProcessState();
        state.setDefinitionVersionId(versionId);
        state.setStateCode(ProcessText.required(bo.stateCode(), "状态编码"));
        state.setDisplayName(bo.displayName());
        StateKind kind = parse(StateKind.class, bo.stateKind(), "状态类别");
        state.setStateKind(kind);
        state.setValidatorKey(bo.validatorKey());
        state.setValidatorVersion(bo.validatorVersion());
        state.setConditionDescription(bo.conditionDescription());
        state.setTerminal(bo.terminal() == null ? kind == StateKind.END : bo.terminal());
        state.setSortOrder(bo.sortOrder());
        return state;
    }

    private ProcessEventDefinition toEvent(long versionId, DefinitionEventBo bo) {
        ProcessEventDefinition event = new ProcessEventDefinition();
        event.setDefinitionVersionId(versionId);
        event.setEventCode(ProcessText.required(bo.eventCode(), "事件编码"));
        event.setDisplayName(bo.displayName());
        event.setEventRole(parse(EventRole.class, bo.eventRole(), "事件角色"));
        event.setEventDirection(parse(EventDirection.class, bo.eventDirection(), "事件方向"));
        event.setEventTypeKey(bo.eventTypeKey());
        event.setEventTypeVersion(bo.eventTypeVersion());
        event.setCallContractKey(bo.callContractKey());
        event.setCallContractVersion(bo.callContractVersion());
        event.setOperationKind(bo.operationKind() == null ? null
            : parse(OperationKind.class, bo.operationKind(), "动作类型"));
        event.setLocalActionKey(bo.localActionKey());
        event.setLocalActionVersion(bo.localActionVersion());
        event.setEffectKey(bo.effectKey());
        event.setLocalEffectKey(bo.localEffectKey());
        event.setSystemCode(bo.systemCode());
        event.setTargetSystemCode(bo.targetSystemCode());
        event.setEndpointRef(bo.endpointRef());
        event.setPurpose(bo.purpose() == null ? null : parse(CallPurpose.class, bo.purpose(), "调用用途"));
        event.setCompletionContract(bo.completionContract() == null ? null
            : parse(CompletionContract.class, bo.completionContract(), "完成合同"));
        event.setSourceStateCode(bo.sourceStateCode());
        event.setTargetStateCode(bo.targetStateCode());
        event.setEnabled(bo.enabled() == null ? Boolean.TRUE : bo.enabled());
        event.setSkipPolicy(bo.skipPolicy() == null ? null : parse(SkipPolicy.class, bo.skipPolicy(), "跳过策略"));
        event.setSkipContractKey(bo.skipContractKey());
        event.setRecoveryForEventCode(bo.recoveryForEventCode());
        event.setPriority(bo.priority() == null ? 0 : bo.priority());
        event.setEditPolicy(bo.editPolicy() == null ? EditPolicy.EDITABLE
            : parse(EditPolicy.class, bo.editPolicy(), "编辑策略"));
        event.setGraphEditPolicy(bo.graphEditPolicy() == null ? GraphEditPolicy.EDITABLE_NEW_VERSION
            : parse(GraphEditPolicy.class, bo.graphEditPolicy(), "图编辑策略"));
        event.setLockedFields(bo.lockedFields());
        event.setRemark(bo.remark());
        return event;
    }

    private ProcessEdge toEdge(long versionId, DefinitionEdgeBo bo) {
        ProcessEdge edge = new ProcessEdge();
        edge.setDefinitionVersionId(versionId);
        edge.setPredecessorEventCode(bo.predecessorEventCode());
        edge.setSuccessorEventCode(bo.successorEventCode());
        edge.setBranchKey(bo.branchKey() == null || bo.branchKey().isBlank() ? "DEFAULT" : bo.branchKey());
        edge.setBranchKind(bo.branchKind() == null ? BranchKind.SEQUENTIAL
            : parse(BranchKind.class, bo.branchKind(), "分支类型"));
        edge.setConditionKey(bo.conditionKey());
        edge.setConditionVersion(bo.conditionVersion());
        edge.setDefaultBranch(bo.defaultBranch());
        edge.setJoinCode(bo.joinCode());
        edge.setPriority(bo.priority() == null ? 0 : bo.priority());
        return edge;
    }

    private ProcessSystemPolicy toPolicy(long versionId, SystemPolicyBo bo) {
        ProcessSystemPolicy policy = new ProcessSystemPolicy();
        policy.setDefinitionVersionId(versionId);
        policy.setSystemCode(ProcessText.required(bo.systemCode(), "系统编码"));
        policy.setPostingMode(parse(PostingMode.class, bo.postingMode(), "过账模式"));
        policy.setFirstConfirmPolicy(bo.firstConfirmPolicy());
        policy.setFailurePolicy(bo.failurePolicy());
        policy.setRemark(bo.remark());
        return policy;
    }

    private DefinitionAggregate loadAggregate(long versionId) {
        PlanDefinition definition = requireDefinition(versionId);
        List<ProcessState> states = stateMapper.selectList(Wrappers.<ProcessState>lambdaQuery()
            .eq(ProcessState::getDefinitionVersionId, versionId).orderByAsc(ProcessState::getSortOrder));
        List<ProcessEventDefinition> events = eventMapper.selectList(Wrappers.<ProcessEventDefinition>lambdaQuery()
            .eq(ProcessEventDefinition::getDefinitionVersionId, versionId)
            .orderByAsc(ProcessEventDefinition::getEventCode));
        List<ProcessEdge> edges = edgeMapper.selectList(Wrappers.<ProcessEdge>lambdaQuery()
            .eq(ProcessEdge::getDefinitionVersionId, versionId).orderByAsc(ProcessEdge::getId));
        List<ProcessSystemPolicy> policies = policyMapper.selectList(Wrappers.<ProcessSystemPolicy>lambdaQuery()
            .eq(ProcessSystemPolicy::getDefinitionVersionId, versionId)
            .orderByAsc(ProcessSystemPolicy::getSystemCode));
        return new DefinitionAggregate(definition, states, events, edges, policies);
    }

    private ProcessState currentState(long versionId, String stateCode) {
        return stateMapper.selectOne(Wrappers.<ProcessState>lambdaQuery()
            .eq(ProcessState::getDefinitionVersionId, versionId)
            .eq(ProcessState::getStateCode, stateCode));
    }

    private ProcessEventDefinition currentEvent(long versionId, String eventCode) {
        return eventMapper.selectOne(Wrappers.<ProcessEventDefinition>lambdaQuery()
            .eq(ProcessEventDefinition::getDefinitionVersionId, versionId)
            .eq(ProcessEventDefinition::getEventCode, eventCode));
    }

    private void ensureStructureValid(long versionId) {
        DefinitionAggregate aggregate = loadAggregate(versionId);
        DefinitionValidationVo validation = definitionValidator.validateStructure(aggregate.definition(),
            aggregate.states(), aggregate.events(), aggregate.edges(), aggregate.policies());
        if (!validation.valid()) {
            throw new ServiceException("草稿结构校验未通过: " + String.join("; ", validation.errors()));
        }
    }

    private EndpointCatalogSnapshot lockCatalog(String expectedRevision) {
        ProcessText.required(expectedRevision, "目录版本");
        try {
            return catalogReader.lockSnapshot(expectedRevision);
        } catch (EndpointCatalogConflictException ex) {
            throw new ServiceException("目录已变化，请重新读取: 当前版本 " + ex.getActualRevision());
        }
    }

    private EndpointCatalogSnapshot lockCurrentCatalog() {
        EndpointCatalogSnapshot snapshot = catalogReader.snapshot();
        return lockCatalog(snapshot.catalogRevision());
    }

    private String requireCurrentEnvironment(EndpointCatalogSnapshot snapshot) {
        String environmentCode = catalogReader.currentEnvironmentCode();
        if (environmentCode == null || environmentCode.isBlank()) {
            throw new ServiceException("当前运行环境未配置");
        }
        boolean enabled = snapshot.environments().stream()
            .anyMatch(item -> environmentCode.equals(item.environmentCode()) && item.enabled());
        if (!enabled) {
            throw new ServiceException("当前运行环境未登记或已停用: " + environmentCode);
        }
        return environmentCode;
    }

    private PolicyEvaluation evaluatePolicies(PlanDefinition definition, DefinitionAggregate aggregate,
                                              PostingModeVectorBo vector, EndpointCatalogSnapshot catalog,
                                              String environmentCode) {
        List<String> errors = new ArrayList<>();
        boolean editable = isEditableBusinessDraft(definition);
        if (!editable) {
            errors.add("仅 BUSINESS 最新草稿可修改系统模式");
        }
        if (vector.expectedVersion() == null || definition.getRevision() == null
            || !vector.expectedVersion().equals(definition.getRevision())) {
            errors.add("版本冲突，当前 revision=" + definition.getRevision());
        }
        if (vector.catalogRevision() == null || vector.catalogRevision().isBlank()) {
            errors.add("目录版本不能为空");
        } else if (!vector.catalogRevision().equals(catalog.catalogRevision())) {
            errors.add("目录已变化，请重新读取: 当前版本 " + catalog.catalogRevision());
        }
        if (vector.environmentCode() == null || !environmentCode.equals(vector.environmentCode())) {
            errors.add("环境与当前运行环境不一致: " + environmentCode);
        }
        List<String> required = DefinitionPostingRules.policySystems(aggregate.events());
        Map<String, SystemPolicyBo> requested = new LinkedHashMap<>();
        if (vector.policies() == null) {
            errors.add("完整系统模式向量不能为空");
        } else {
            for (SystemPolicyBo policy : vector.policies()) {
                if (policy == null || policy.systemCode() == null || policy.systemCode().isBlank()) {
                    errors.add("系统模式缺少系统编码");
                    continue;
                }
                if (requested.putIfAbsent(policy.systemCode(), policy) != null) {
                    errors.add("系统模式重复: " + policy.systemCode());
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(requested.keySet());
        if (!missing.isEmpty()) {
            errors.add("完整向量缺少系统: " + String.join(",", missing));
        }
        for (String code : requested.keySet()) {
            if (!required.contains(code)) {
                errors.add("向量包含非政策系统: " + code);
            }
        }
        Map<String, PostingMode> modes = new LinkedHashMap<>();
        for (String code : required) {
            SystemPolicyBo policy = requested.get(code);
            if (policy == null) {
                continue;
            }
            if (policy.postingMode() == null || policy.postingMode().isBlank()) {
                errors.add("系统模式缺失: " + code);
                continue;
            }
            try {
                modes.put(code, PostingMode.valueOf(policy.postingMode().trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                errors.add("系统模式不合法: " + code + " / " + policy.postingMode());
            }
            boolean registered = catalog.systems().stream().anyMatch(item -> code.equals(item.systemCode()));
            if (!registered) {
                errors.add("系统未在目录登记: " + code);
            }
        }
        errors.addAll(DefinitionPostingRules.validateBypassContracts(definition, aggregate.events(),
            aggregate.edges(), modes));
        List<String> bypassSystems = new ArrayList<>();
        List<String> affected = new ArrayList<>();
        List<String> unaffected = new ArrayList<>();
        for (Map.Entry<String, PostingMode> entry : modes.entrySet()) {
            if (entry.getValue() == PostingMode.BYPASS) {
                bypassSystems.add(entry.getKey());
            }
        }
        for (ProcessEventDefinition event : aggregate.events()) {
            if (Boolean.FALSE.equals(event.getEnabled())) {
                continue;
            }
            if (DefinitionPostingRules.skippedByMode(definition, event, modes)) {
                affected.add(event.getEventCode());
            } else {
                unaffected.add(event.getEventCode());
            }
        }
        List<SystemPolicyBo> policies = new ArrayList<>();
        Map<String, String> beforeModes = new LinkedHashMap<>();
        for (ProcessSystemPolicy policy : aggregate.policies()) {
            beforeModes.put(policy.getSystemCode(), policy.getPostingMode() == null ? null
                : policy.getPostingMode().name());
        }
        List<SystemModeDiffVo> modeDiffs = new ArrayList<>();
        for (String code : required) {
            SystemPolicyBo policy = requested.get(code);
            if (policy != null) {
                policies.add(policy);
            }
            String before = beforeModes.get(code);
            PostingMode after = modes.get(code);
            String afterName = after == null ? null : after.name();
            modeDiffs.add(new SystemModeDiffVo(code, before, afterName, !Objects.equals(before, afterName)));
        }
        PostingImpactVO impact = new PostingImpactVO(errors.isEmpty(), ProcessText.id(definition.getRevision()),
            catalog.catalogRevision(), List.copyOf(bypassSystems), List.copyOf(affected), List.copyOf(unaffected),
            List.copyOf(errors), environmentCode, List.copyOf(policies), List.copyOf(modeDiffs));
        return new PolicyEvaluation(impact, modes);
    }

    private ExternalSystemRowVO externalSystemRow(PlanDefinition definition, List<ProcessEventDefinition> events,
                                                  String systemCode, boolean policyOwner, boolean editable,
                                                  PostingMode currentMode, EndpointCatalogSnapshot catalog,
                                                  String environmentCode,
                                                  List<ProcessEventDefinition> callerEvents) {
        DefinitionPostingRules.EventGroups groups = DefinitionPostingRules.groupEvents(definition, events, systemCode);
        Set<String> unaffected = new LinkedHashSet<>(groups.unaffected());
        Set<String> disabled = new LinkedHashSet<>(groups.disabled());
        for (ProcessEventDefinition event : callerEvents) {
            if (Boolean.FALSE.equals(event.getEnabled())) {
                disabled.add(event.getEventCode());
            } else {
                unaffected.add(event.getEventCode());
            }
        }
        EndpointSystemSnapshot system = catalog.systems().stream()
            .filter(item -> systemCode.equals(item.systemCode())).findFirst().orElse(null);
        List<String> reasons = new ArrayList<>();
        if (system == null) {
            reasons.add("系统未在目录登记");
        } else if (!system.enabled()) {
            reasons.add("系统已在目录停用");
        }
        List<String> endpoints = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        for (EndpointSystemAddressSnapshot link : catalog.systemAddresses()) {
            if (!systemCode.equals(link.systemCode())) {
                continue;
            }
            EndpointAddressSnapshot address = catalog.addresses().stream()
                .filter(item -> item.addressId().equals(link.addressId())).findFirst().orElse(null);
            if (address != null && environmentCode.equals(address.environmentCode())) {
                refs.add(address.endpointRef());
            }
        }
        for (String ref : refs) {
            EndpointAddressResolution resolution = catalog.resolveEndpoint(environmentCode, systemCode, ref);
            endpoints.add(ref + "：" + statusText(resolution));
        }
        if (endpoints.isEmpty()) {
            reasons.add("当前环境未配置地址");
        }
        boolean selfSystem = systemCode.equals(definition.getSelfSystemCode());
        if (selfSystem) {
            reasons.add("本系统事件");
        }
        if (!policyOwner) {
            reasons.add(callerEvents.isEmpty() ? "无政策归属" : "仅入站来源，无政策归属");
        }
        if (policyOwner && groups.bypassable().isEmpty()) {
            reasons.add("仅入站/查询/控制，不支持屏蔽抛账");
        }
        List<String> allowedModes = new ArrayList<>();
        if (policyOwner) {
            allowedModes.add(PostingMode.AUTOMATIC.name());
            allowedModes.add(PostingMode.MANUAL_CONFIRM.name());
            if (!groups.bypassable().isEmpty()) {
                allowedModes.add(PostingMode.BYPASS.name());
            }
        }
        return new ExternalSystemRowVO(systemCode, system == null ? null : system.displayName(), environmentCode,
            currentMode == null ? null : currentMode.name(), policyOwner && editable, policyOwner,
            groups.bypassable(), List.copyOf(unaffected), List.copyOf(disabled), endpoints, reasons, allowedModes,
            selfSystem, system != null);
    }

    private Map<String, List<ProcessEventDefinition>> callerEvents(List<ProcessEventDefinition> events) {
        Map<String, List<ProcessEventDefinition>> result = new LinkedHashMap<>();
        for (ProcessEventDefinition event : events) {
            if (event.getEventDirection() != EventDirection.INBOUND
                || event.getCallContractKey() == null || event.getCallContractVersion() == null) {
                continue;
            }
            try {
                for (String caller : definitionValidator.callerCandidates(event.getCallContractKey(),
                    event.getCallContractVersion())) {
                    if (caller != null && !caller.isBlank()) {
                        result.computeIfAbsent(caller.trim(), key -> new ArrayList<>()).add(event);
                    }
                }
            } catch (ServiceException ignored) {
                // 能力缺失由发布结构校验报告；汇总不伪造来源。
            }
        }
        return result;
    }

    private static String statusText(EndpointAddressResolution resolution) {
        return switch (resolution.status()) {
            case ENABLED -> "启用";
            case DISABLED -> "停用";
            case UNCONFIGURED -> "未配置";
            case UNLINKED -> "未关联";
            case UNREGISTERED -> "未登记";
            case AMBIGUOUS -> "匹配不唯一";
        };
    }

    private List<String> validatePublishCatalog(DefinitionAggregate aggregate, EndpointCatalogSnapshot catalog,
                                                 String environmentCode) {
        List<String> errors = new ArrayList<>();
        for (String code : DefinitionPostingRules.policySystems(aggregate.events())) {
            EndpointSystemSnapshot system = catalog.systems().stream()
                .filter(item -> code.equals(item.systemCode())).findFirst().orElse(null);
            if (system == null) {
                errors.add("系统未在目录登记: " + code);
            } else if (!system.enabled()) {
                errors.add("系统已在目录停用: " + code);
            }
        }
        Map<String, PostingMode> modes = new LinkedHashMap<>();
        for (ProcessSystemPolicy policy : aggregate.policies()) {
            if (policy.getPostingMode() != null) {
                modes.put(policy.getSystemCode(), policy.getPostingMode());
            }
        }
        for (ProcessEventDefinition event : aggregate.events()) {
            if (event.getEventDirection() != EventDirection.OUTBOUND
                || Boolean.FALSE.equals(event.getEnabled())) {
                continue;
            }
            if (DefinitionPostingRules.skippedByMode(aggregate.definition(), event, modes)) {
                continue;
            }
            EndpointAddressResolution resolution = catalog.resolveEndpoint(environmentCode,
                event.getTargetSystemCode(), event.getEndpointRef());
            if (!resolution.resolved()) {
                errors.add("事件[" + event.getEventCode() + "]发送地址不可用: "
                    + statusText(resolution) + "（" + resolution.message() + "）");
            }
        }
        return errors;
    }

    private DefinitionSummaryVo summary(PlanDefinition definition) {
        return new DefinitionSummaryVo(ProcessText.id(definition.getId()), definition.getPlanCode(),
            definition.getPlanVersion(), definition.getPlanName(), definition.getPlanType(),
            definition.getPlanRole() == null ? null : definition.getPlanRole().name(),
            definition.getStatus() == null ? null : definition.getStatus().name(),
            ProcessText.id(definition.getRevision()),
            definition.getPlanCode().equals(definition.getLatestKey())
                && definition.getStatus() == DefinitionStatus.DRAFT,
            definition.getStatus() == DefinitionStatus.ACTIVE, definition.getPublishedAt());
    }

    private DefinitionDetailVo detail(DefinitionAggregate aggregate) {
        PlanDefinition definition = aggregate.definition();
        List<DefinitionStateBo> states = aggregate.states().stream()
            .map(item -> new DefinitionStateBo(item.getStateCode(), item.getDisplayName(),
                item.getStateKind() == null ? null : item.getStateKind().name(), item.getValidatorKey(),
                item.getValidatorVersion(), item.getConditionDescription(), item.getTerminal(),
                item.getSortOrder())).toList();
        List<DefinitionEventBo> events = aggregate.events().stream().map(this::eventBo).toList();
        List<DefinitionEdgeBo> edges = aggregate.edges().stream()
            .map(item -> new DefinitionEdgeBo(item.getPredecessorEventCode(), item.getSuccessorEventCode(),
                item.getBranchKey(), item.getBranchKind() == null ? null : item.getBranchKind().name(),
                item.getConditionKey(), item.getConditionVersion(), item.getDefaultBranch(),
                item.getJoinCode(), item.getPriority())).toList();
        List<SystemPolicyBo> policies = aggregate.policies().stream()
            .map(item -> new SystemPolicyBo(item.getSystemCode(), item.getPostingMode() == null ? null
                : item.getPostingMode().name(), item.getFirstConfirmPolicy(), item.getFailurePolicy(),
                item.getRemark())).toList();
        return new DefinitionDetailVo(summary(definition), definition.getSelfSystemCode(),
            definition.getBusinessType(), definition.getBusinessScheduleCode(),
            definition.getMode() == null ? null : definition.getMode().name(),
            definition.getActivePlanLimit(), definition.getFairnessWindowSize(),
            definition.getMaxExecutionMs(), definition.getCompletionPolicy() == null ? null
                : definition.getCompletionPolicy().name(), definition.getExceptionStateCode(), states, events, edges,
            policies, definition.getRoundBatchLimit(), definition.getRoundMaxDurationMs(),
            definition.getWorkerBatchLimit(), definition.getWorkerMaxDurationMs(), definition.getLeaseMs(),
            definition.getRetryMax(), definition.getRetryBackoffMs(), definition.getScheduleIntervalMs(),
            definition.getScheduleCron(), definition.getJobTimeoutMs(), definition.getJobMaxRetryTimes(),
            definition.getDescription());
    }

    private DefinitionEventBo eventBo(ProcessEventDefinition event) {
        return new DefinitionEventBo(event.getEventCode(), event.getDisplayName(),
            event.getEventRole() == null ? null : event.getEventRole().name(),
            event.getEventDirection() == null ? null : event.getEventDirection().name(),
            event.getEventTypeKey(), event.getEventTypeVersion(), event.getCallContractKey(),
            event.getCallContractVersion(), event.getOperationKind() == null ? null : event.getOperationKind().name(),
            event.getLocalActionKey(), event.getLocalActionVersion(), event.getEffectKey(),
            event.getLocalEffectKey(), event.getSystemCode(), event.getTargetSystemCode(), event.getEndpointRef(),
            event.getPurpose() == null ? null : event.getPurpose().name(),
            event.getCompletionContract() == null ? null : event.getCompletionContract().name(),
            event.getSourceStateCode(), event.getTargetStateCode(), event.getEnabled(),
            event.getSkipPolicy() == null ? null : event.getSkipPolicy().name(), event.getSkipContractKey(),
            event.getRecoveryForEventCode(), event.getPriority(),
            event.getEditPolicy() == null ? null : event.getEditPolicy().name(),
            event.getGraphEditPolicy() == null ? null : event.getGraphEditPolicy().name(),
            event.getLockedFields(), event.getRemark());
    }

    private void appendAudit(ActionType action, String target, long versionId, Long revision, String commandKey,
                             String reason, String payloadHash, String result) {
        ProcessActionAudit audit = auditService.newAudit(action, ActionSource.USER, AuditResult.SUCCESS);
        audit.setDefinitionVersionId(versionId);
        audit.setAfterVersion(revision);
        audit.setCommandKey(commandKey);
        audit.setReason(truncate(reason, 1000));
        audit.setReceiptKey(commandReceiptService.key(action, target, commandKey));
        audit.setPayloadSummary(truncate(payloadHash, 2000));
        audit.setDecisionSummary(truncate(result, 2000));
        auditService.append(audit);
    }

    private DefinitionDetailVo replayDetail(ActionType action, String target, String commandKey, String payload) {
        ProcessActionAudit existing = commandReceiptService.find(action, target, commandKey);
        if (existing == null) {
            return null;
        }
        commandReceiptService.checkSamePayload(existing, payload);
        return detailFromResult(existing.getDecisionSummary(), existing.getAfterVersion(), null);
    }

    private DefinitionDetailVo replayDetailCurrent(ActionType action, String target, String commandKey,
                                                   String payload) {
        String receiptKey = commandReceiptService.key(action, target, commandKey);
        DefinitionReceiptStore.Receipt existing = receiptStore.findCurrent(receiptKey);
        if (existing == null) {
            return null;
        }
        if (!payload.equals(existing.payloadSummary())) {
            throw new ServiceException("同一 commandKey 的参数不同，拒绝复用原命令");
        }
        return detailFromResult(existing.decisionSummary(), existing.afterVersion(), null);
    }

    private DefinitionDetailVo detailFromResult(String encoded, Long revision, String fallback) {
        StoredResult stored = decode(encoded);
        Long versionId = stored == null ? null : stored.versionId();
        if (versionId == null) {
            throw new ServiceException("命令回执缺少原结果，不能安全重投");
        }
        DefinitionDetailVo current = get(versionId);
        String targetRevision = ProcessText.id(stored.revision() == null ? revision : stored.revision());
        if (Objects.equals(current.summary().revision(), targetRevision)) {
            return current;
        }
        DefinitionSummaryVo summary = current.summary();
        return new DefinitionDetailVo(new DefinitionSummaryVo(summary.versionId(), summary.planCode(),
            summary.planVersion(), summary.planName(), summary.planType(), summary.planRole(), summary.status(),
            targetRevision, summary.latest(), summary.active(), summary.publishedAt()), current.selfSystemCode(),
            current.businessType(), current.businessScheduleCode(), current.mode(), current.activePlanLimit(),
            current.fairnessWindowSize(), current.maxExecutionMs(), current.completionPolicy(),
            current.exceptionStateCode(), current.states(), current.events(), current.edges(),
            current.systemPolicies(), current.roundBatchLimit(), current.roundMaxDurationMs(),
            current.workerBatchLimit(), current.workerMaxDurationMs(), current.leaseMs(), current.retryMax(),
            current.retryBackoffMs(), current.scheduleIntervalMs(), current.scheduleCron(), current.jobTimeoutMs(),
            current.jobMaxRetryTimes(), current.description());
    }

    private List<FieldCapabilityVo> fieldCapabilities(PlanDefinition definition, boolean editable) {
        List<FieldCapabilityVo> fields = new ArrayList<>();
        String readOnly = editable ? null : "仅最新草稿可编辑";
        fields.add(field("planCode", false, "CODE_FIXED", "计划编码为身份字段"));
        fields.add(field("planVersion", false, "CODE_FIXED", "业务版本仅由复制分配"));
        fields.add(field("planRole", false, "CODE_FIXED", "计划角色为代码固定字段"));
        fields.add(field("planType", false, "CODE_FIXED", "计划类型为身份字段"));
        fields.add(field("editPolicy", false, "CODE_FIXED", "由注册能力确定"));
        fields.add(field("graphEditPolicy", false, "CODE_FIXED", "由注册能力确定"));
        fields.add(field("lockedFields", false, "CODE_FIXED", "由注册能力确定"));
        for (String name : List.of("planName", "businessType", "businessScheduleCode", "selfSystemCode",
            "completionPolicy", "exceptionStateCode", "roundBatchLimit", "roundMaxDurationMs",
            "workerBatchLimit", "workerMaxDurationMs", "leaseMs", "retryMax", "retryBackoffMs",
            "scheduleIntervalMs", "scheduleCron", "jobTimeoutMs", "jobMaxRetryTimes", "description", "maxExecutionMs")) {
            fields.add(field(name, editable, "EDITABLE", readOnly));
        }
        boolean supervision = definition.getPlanRole() == PlanRole.SUPERVISOR;
        for (String name : List.of("mode", "activePlanLimit", "fairnessWindowSize")) {
            fields.add(supervision ? field(name, editable, "EDITABLE", readOnly)
                : field(name, false, "CODE_FIXED", "BUSINESS 计划不使用监督参数"));
        }
        return fields;
    }

    private static FieldCapabilityVo field(String name, boolean editable, String policy, String reason) {
        return new FieldCapabilityVo(name, editable, policy, reason);
    }

    private EventEditCapabilityVo eventCapability(ProcessEventDefinition event, boolean editable) {
        boolean codeFixed = event.getEditPolicy() == EditPolicy.CODE_FIXED;
        boolean graphFixed = event.getGraphEditPolicy() == GraphEditPolicy.CODE_FIXED;
        List<String> locked = new ArrayList<>();
        if (event.getLockedFields() != null && !event.getLockedFields().isBlank()) {
            for (String item : event.getLockedFields().split("[,，\\s]+")) {
                if (!item.isBlank()) {
                    locked.add(item);
                }
            }
        }
        String reason = codeFixed ? "需代码重构并新建版本" : (editable ? null : "仅最新草稿可编辑");
        return new EventEditCapabilityVo(event.getEventCode(),
            event.getEditPolicy() == null ? null : event.getEditPolicy().name(),
            event.getGraphEditPolicy() == null ? null : event.getGraphEditPolicy().name(),
            editable && !codeFixed, editable && !graphFixed, editable && !codeFixed && !graphFixed, locked, reason);
    }

    private DefinitionValidationVo replayPublish(ActionType action, String target, String commandKey,
                                                 String payload) {
        ProcessActionAudit existing = commandReceiptService.find(action, target, commandKey);
        if (existing == null) {
            return null;
        }
        commandReceiptService.checkSamePayload(existing, payload);
        return publishFromResult(existing.getDecisionSummary(), existing.getAfterVersion());
    }

    private DefinitionValidationVo replayPublishCurrent(ActionType action, String target, String commandKey,
                                                         String payload) {
        String receiptKey = commandReceiptService.key(action, target, commandKey);
        DefinitionReceiptStore.Receipt existing = receiptStore.findCurrent(receiptKey);
        if (existing == null) {
            return null;
        }
        if (!payload.equals(existing.payloadSummary())) {
            throw new ServiceException("同一 commandKey 的参数不同，拒绝复用原命令");
        }
        return publishFromResult(existing.decisionSummary(), existing.afterVersion());
    }

    private DefinitionValidationVo publishFromResult(String encoded, Long revision) {
        StoredResult stored = decode(encoded);
        if (stored == null || stored.versionId() == null) {
            throw new ServiceException("发布回执缺少原结果，不能安全重投");
        }
        return new DefinitionValidationVo(true, ProcessText.id(stored.versionId()),
            ProcessText.id(stored.revision() == null ? revision : stored.revision()), List.of(), stored.extra());
    }

    private PostingPolicySaveResultVo replayPolicies(ActionType action, String target, String commandKey,
                                                     String payload) {
        ProcessActionAudit existing = commandReceiptService.find(action, target, commandKey);
        if (existing == null) {
            return null;
        }
        commandReceiptService.checkSamePayload(existing, payload);
        return policyResultFrom(existing.getDecisionSummary());
    }

    private PostingPolicySaveResultVo replayPoliciesCurrent(ActionType action, String target, String commandKey,
                                                            String payload) {
        String receiptKey = commandReceiptService.key(action, target, commandKey);
        DefinitionReceiptStore.Receipt existing = receiptStore.findCurrent(receiptKey);
        if (existing == null) {
            return null;
        }
        if (!payload.equals(existing.payloadSummary())) {
            throw new ServiceException("同一 commandKey 的参数不同，拒绝复用原命令");
        }
        return policyResultFrom(existing.decisionSummary());
    }

    private PostingPolicySaveResultVo policyResultFrom(String encoded) {
        StoredResult stored = decode(encoded);
        if (stored == null || stored.versionId() == null) {
            throw new ServiceException("系统模式回执缺少原结果，不能安全重投");
        }
        DefinitionAggregate aggregate = loadAggregate(stored.versionId());
        List<SystemPolicyBo> policies = aggregate.policies().stream()
            .map(item -> new SystemPolicyBo(item.getSystemCode(), item.getPostingMode() == null ? null
                : item.getPostingMode().name(), item.getFirstConfirmPolicy(), item.getFailurePolicy(),
                item.getRemark())).toList();
        String catalogRevision = stored.extra().size() > 0 ? stored.extra().get(0) : null;
        String environmentCode = stored.extra().size() > 1 ? stored.extra().get(1) : null;
        List<String> bypass = stored.extra().size() > 2
            ? List.of(stored.extra().get(2).split(",", -1)) : List.of();
        return new PostingPolicySaveResultVo(true, ProcessText.id(stored.revision()), catalogRevision,
            environmentCode, policies, bypass.stream().filter(item -> !item.isBlank()).toList(), List.of(),
            List.of(), List.of(), List.of());
    }

    private boolean replayDelete(ActionType action, String target, String commandKey, String payload) {
        ProcessActionAudit existing = commandReceiptService.find(action, target, commandKey);
        if (existing == null) {
            return false;
        }
        commandReceiptService.checkSamePayload(existing, payload);
        return true;
    }

    private boolean replayDeleteCurrent(ActionType action, String target, String commandKey, String payload) {
        String receiptKey = commandReceiptService.key(action, target, commandKey);
        DefinitionReceiptStore.Receipt existing = receiptStore.findCurrent(receiptKey);
        if (existing == null) {
            return false;
        }
        if (!payload.equals(existing.payloadSummary())) {
            throw new ServiceException("同一 commandKey 的参数不同，拒绝复用原命令");
        }
        return true;
    }

    private static String commandTarget(String kind, long versionId) {
        return DefinitionCommandSupport.target(kind, Long.toString(versionId));
    }

    private static String encodeDetail(long versionId, Long revision, DefinitionStatus status) {
        return encode("D", versionId, revision, status == null ? null : status.name(), List.of());
    }

    private static String encodePublish(DefinitionValidationVo result) {
        return encode("P", Long.parseLong(result.versionId()), Long.parseLong(result.revision()), "PUBLISHED",
            result.warnings());
    }

    private static String encodePolicyResult(long versionId, Long revision, String catalogRevision,
                                             String environmentCode, List<String> bypassSystems) {
        return encode("S", versionId, revision, "POLICY", List.of(catalogRevision == null ? "" : catalogRevision,
            environmentCode == null ? "" : environmentCode, String.join(",", bypassSystems)));
    }

    private static String encode(String kind, Long versionId, Long revision, String status, List<String> extra) {
        String joined = String.join("\n", extra == null ? List.of() : extra);
        String encodedExtra = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(joined.getBytes(StandardCharsets.UTF_8));
        return String.join("|", kind, versionId == null ? "" : versionId.toString(),
            revision == null ? "" : revision.toString(), status == null ? "" : status, encodedExtra);
    }

    private static StoredResult decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length < 5) {
            return null;
        }
        try {
            Long versionId = parts[1].isBlank() ? null : Long.valueOf(parts[1]);
            Long revision = parts[2].isBlank() ? null : Long.valueOf(parts[2]);
            String extraText = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            List<String> extra = extraText.isEmpty() ? List.of() : List.of(extraText.split("\n", -1));
            return new StoredResult(parts[0], versionId, revision, parts[3], extra);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String contentDigest(DefinitionAggregate aggregate) {
        StringBuilder builder = new StringBuilder();
        builder.append(aggregate.definition().getPlanCode()).append('/')
            .append(aggregate.definition().getPlanVersion()).append('/')
            .append(aggregate.definition().getRevision()).append('|');
        for (ProcessEventDefinition event : aggregate.events()) {
            builder.append(event.getEventCode()).append(':').append(event.getEventDirection()).append(':')
                .append(event.getCallContractKey()).append('@').append(event.getCallContractVersion()).append(':')
                .append(event.getLocalActionKey()).append('@').append(event.getLocalActionVersion()).append(';');
        }
        for (ProcessSystemPolicy policy : aggregate.policies()) {
            builder.append(policy.getSystemCode()).append('=').append(policy.getPostingMode()).append(';');
        }
        return ProcessText.sha256(builder.toString());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        List<String> errors = new ArrayList<>();
        E result = ProcessText.parse(type, field, value, errors);
        if (!errors.isEmpty()) {
            throw new ServiceException(String.join("; ", errors));
        }
        return result;
    }

    private ProcessState copyState(ProcessState source) {
        ProcessState target = new ProcessState();
        target.setStateCode(source.getStateCode());
        target.setDisplayName(source.getDisplayName());
        target.setStateKind(source.getStateKind());
        target.setValidatorKey(source.getValidatorKey());
        target.setValidatorVersion(source.getValidatorVersion());
        target.setConditionDescription(source.getConditionDescription());
        target.setTerminal(source.getTerminal());
        target.setSortOrder(source.getSortOrder());
        return target;
    }

    private ProcessEventDefinition copyEvent(ProcessEventDefinition source) {
        ProcessEventDefinition target = new ProcessEventDefinition();
        target.setEventCode(source.getEventCode());
        target.setDisplayName(source.getDisplayName());
        target.setEventRole(source.getEventRole());
        target.setEventDirection(source.getEventDirection());
        target.setEventTypeKey(source.getEventTypeKey());
        target.setEventTypeVersion(source.getEventTypeVersion());
        target.setCallContractKey(source.getCallContractKey());
        target.setCallContractVersion(source.getCallContractVersion());
        target.setOperationKind(source.getOperationKind());
        target.setLocalActionKey(source.getLocalActionKey());
        target.setLocalActionVersion(source.getLocalActionVersion());
        target.setEffectKey(source.getEffectKey());
        target.setLocalEffectKey(source.getLocalEffectKey());
        target.setSystemCode(source.getSystemCode());
        target.setTargetSystemCode(source.getTargetSystemCode());
        target.setEndpointRef(source.getEndpointRef());
        target.setPurpose(source.getPurpose());
        target.setCompletionContract(source.getCompletionContract());
        target.setSourceStateCode(source.getSourceStateCode());
        target.setTargetStateCode(source.getTargetStateCode());
        target.setEnabled(source.getEnabled());
        target.setSkipPolicy(source.getSkipPolicy());
        target.setSkipContractKey(source.getSkipContractKey());
        target.setRecoveryForEventCode(source.getRecoveryForEventCode());
        target.setPriority(source.getPriority());
        target.setEditPolicy(source.getEditPolicy());
        target.setGraphEditPolicy(source.getGraphEditPolicy());
        target.setLockedFields(source.getLockedFields());
        target.setRemark(source.getRemark());
        return target;
    }

    private ProcessEdge copyEdge(ProcessEdge source) {
        ProcessEdge target = new ProcessEdge();
        target.setPredecessorEventCode(source.getPredecessorEventCode());
        target.setSuccessorEventCode(source.getSuccessorEventCode());
        target.setBranchKey(source.getBranchKey());
        target.setBranchKind(source.getBranchKind());
        target.setConditionKey(source.getConditionKey());
        target.setConditionVersion(source.getConditionVersion());
        target.setDefaultBranch(source.getDefaultBranch());
        target.setJoinCode(source.getJoinCode());
        target.setPriority(source.getPriority());
        return target;
    }

    private ProcessSystemPolicy copyPolicy(ProcessSystemPolicy source) {
        ProcessSystemPolicy target = new ProcessSystemPolicy();
        target.setSystemCode(source.getSystemCode());
        target.setPostingMode(source.getPostingMode());
        target.setFirstConfirmPolicy(source.getFirstConfirmPolicy());
        target.setFailurePolicy(source.getFailurePolicy());
        target.setRemark(source.getRemark());
        return target;
    }

    private record DefinitionAggregate(PlanDefinition definition, List<ProcessState> states,
                                       List<ProcessEventDefinition> events, List<ProcessEdge> edges,
                                       List<ProcessSystemPolicy> policies) {
    }

    private record PolicyEvaluation(PostingImpactVO impact, Map<String, PostingMode> modes) {
    }

    private record StoredResult(String kind, Long versionId, Long revision, String status, List<String> extra) {
    }
}
