/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布计划初始化与同步发布入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import com.innolux.iws.process.api.BusinessScheduleBo;
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionDraftBo;
import com.innolux.iws.process.api.DefinitionEventBo;
import com.innolux.iws.process.api.DefinitionStateBo;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.api.PlanTypeBo;
import com.innolux.iws.process.api.PlanTypeVo;
import com.innolux.iws.process.api.SystemPolicyBo;
import com.innolux.iws.process.runtime.ImmediateExecutionService;
import com.innolux.iws.process.runtime.SupervisorRules;
import com.innolux.iws.process.service.BusinessScheduleService;
import com.innolux.iws.process.service.PlanDefinitionService;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * APK 发布计划的应用入口。
 *
 * <p>只有上传业务完成本地版本落库后才调用 {@link #publish(long, String)}；只有管理员显式
 * 初始化或核验时才调用 {@link #ensurePlan(String, String)}。本类没有启动钩子，process 关闭时
 * 直接拒绝，不绕过平台直接改状态。</p>
 */
@Service
public class ApkPublicationProcess {

    private static final String PROCESS_ENABLED = "iws.process.enabled";

    private static final int PAGE_SIZE = 50;

    private static final int PLAN_COMMAND_SUFFIX_RESERVE = 24;

    private static final String SUCCEEDED = "SUCCEEDED";

    private final Environment environment;

    private final ObjectProvider<ImmediateExecutionService> immediateProvider;

    private final ObjectProvider<BusinessScheduleService> scheduleProvider;

    private final ObjectProvider<PlanDefinitionService> definitionProvider;

    private final ObjectProvider<EndpointCatalogReader> catalogProvider;

    private final ApkPlanAdminGuard planAdminGuard;

    /**
     * 构造发布计划入口。
     *
     * @param environment 运行属性
     * @param immediateProvider 即时执行服务
     * @param scheduleProvider 业务调度服务
     * @param definitionProvider 计划定义服务
     * @param catalogProvider 端点目录读取器
     * @param planAdminGuard 管理员操作门禁
     */
    public ApkPublicationProcess(Environment environment,
                                 ObjectProvider<ImmediateExecutionService> immediateProvider,
                                 ObjectProvider<BusinessScheduleService> scheduleProvider,
                                 ObjectProvider<PlanDefinitionService> definitionProvider,
                                 ObjectProvider<EndpointCatalogReader> catalogProvider,
                                 ApkPlanAdminGuard planAdminGuard) {
        this.environment = environment;
        this.immediateProvider = immediateProvider;
        this.scheduleProvider = scheduleProvider;
        this.definitionProvider = definitionProvider;
        this.catalogProvider = catalogProvider;
        this.planAdminGuard = planAdminGuard;
    }

    /**
     * 同步执行一次 APK 发布。
     *
     * <p>只有平台返回 {@code SUCCEEDED} 且带有有效实例 ID 时才返回；
     * {@code PROCESSING}、{@code NOT_READY}、{@code BUSY}、{@code UNKNOWN}、
     * 超时或其他结果都抛错，不能伪造成发布成功。</p>
     *
     * @param releaseId 已上传的 APK 版本 ID
     * @param inputHash 上传输入摘要
     * @return process 计划实例 ID
     */
    public String publish(long releaseId, String inputHash) {
        if (releaseId <= 0) {
            throw new ServiceException("releaseId 必须为正整数");
        }
        String hash = ProcessText.required(inputHash, "inputHash");
        if (hash.length() > ApkProcessContract.INPUT_HASH_MAX_LENGTH) {
            throw new ServiceException("inputHash 超过长度限制: "
                + ApkProcessContract.INPUT_HASH_MAX_LENGTH);
        }
        requireProcessEnabled();
        requireCatalogReady();
        ImmediateExecutionService immediate = require(immediateProvider, "即时计划执行服务");
        BusinessScheduleService schedules = require(scheduleProvider, "业务调度服务");
        BusinessScheduleVo schedule = requireSchedule(schedules);
        requirePlanType(schedule);
        String businessId = Long.toString(releaseId);
        String commandKey = "APK_PUBLISH:" + releaseId + ":" + hash;
        if (commandKey.length() > ApkProcessContract.COMMAND_KEY_MAX_LENGTH) {
            throw new ServiceException("APK 发布命令键超过长度限制");
        }
        long scheduleId = parsePositiveLong(schedule.id(), "scheduleId");
        // 只有持久化回执证明从未准入时才开始下一次尝试。键由前一次键派生，
        // 并发重试和响应丢失仍定位同一尝试；有实例或 UNKNOWN 时始终回读原键。
        while (true) {
            ImmediateExecutionVO previous = immediate.find(scheduleId, commandKey);
            if (previous == null || !"NOT_READY".equals(previous.resultCode())
                || previous.instanceId() != null || previous.reconciliationRequired()
                || previous.finishedAt() == null) {
                break;
            }
            commandKey = "APK_PUBLISH:" + releaseId + ":" + ProcessText.sha256(commandKey + ":retry");
        }
        ImmediateExecutionVO result = immediate.execute(new ExecuteImmediateCommand(
            scheduleId, ApkProcessContract.PLAN_TYPE,
            ApkProcessContract.BUSINESS_TYPE, businessId, businessId, hash, commandKey));
        if (result == null || !SUCCEEDED.equals(result.resultCode())
            || !SUCCEEDED.equals(result.effectResult())) {
            throw notSucceeded(result);
        }
        String instanceId = ProcessText.required(result.instanceId(), "instanceId");
        parsePositiveLong(instanceId, "instanceId");
        return instanceId;
    }

    /**
     * 显式初始化或核验 APK 发布计划。
     *
     * <p>已存在完全匹配的生效版本时只读核验；缺失时通过平台服务创建草稿、校验并发布。
     * 不修改已生效定义，不直接写 process 状态表，也不在应用启动时调用。</p>
     *
     * @param commandKey 幂等命令键
     * @param reason 中文操作原因
     * @return 计划版本事实
     */
    public PlanProvisioningResult ensurePlan(String commandKey, String reason) {
        planAdminGuard.check();
        requireProcessEnabled();
        String key = ProcessText.required(commandKey, "commandKey");
        if (key.length() > ApkProcessContract.COMMAND_KEY_MAX_LENGTH - PLAN_COMMAND_SUFFIX_RESERVE) {
            throw new ServiceException("计划初始化 commandKey 过长");
        }
        String why = ProcessText.required(reason, "reason");
        BusinessScheduleService schedules = require(scheduleProvider, "业务调度服务");
        PlanDefinitionService definitions = require(definitionProvider, "计划定义服务");
        requireCatalogReady();
        BusinessScheduleVo schedule = ensureSchedule(schedules, key, why);
        ensurePlanType(schedules, schedule, key, why);
        ProvisionedPlan supervisor = ensureSupervisorDefinition(definitions, key, why);
        PlanProvisioningResult business = ensureDefinition(definitions, key, why);
        return new PlanProvisioningResult(business.versionId(), business.revision(),
            ApkProcessContract.PLAN_CODE, ApkProcessContract.PLAN_TYPE,
            business.created() || supervisor.created(), true,
            supervisor.versionId(), supervisor.revision());
    }

    private void requireCatalogReady() {
        EndpointCatalogReader reader = require(catalogProvider, "系统目录服务");
        EndpointCatalogSnapshot snapshot = reader.snapshot();
        validateCatalogReady(snapshot, reader.currentEnvironmentCode());
    }

    static void validateCatalogReady(EndpointCatalogSnapshot snapshot, String currentEnvironmentCode) {
        String environmentCode = currentEnvironmentCode == null ? "" : currentEnvironmentCode.trim();
        if (environmentCode.isEmpty()) {
            throw new ServiceException("当前接入环境未配置：iws.endpoint.current-environment 为空，"
                + "请设置环境码并在“系统接入配置 → 环境配置”启用该环境");
        }
        EndpointEnvironmentSnapshot environment = snapshot.environments().stream()
            .filter(item -> environmentCode.equals(item.environmentCode()))
            .findFirst()
            .orElse(null);
        if (environment == null) {
            throw new ServiceException("当前接入环境不存在：" + environmentCode
                + "，请在“系统接入配置 → 环境配置”新增并启用该环境");
        }
        if (!environment.enabled()) {
            throw new ServiceException("当前接入环境未启用：" + environmentCode
                + "，请在“系统接入配置 → 环境配置”启用该环境");
        }
        EndpointSystemSnapshot system = snapshot.systems().stream()
            .filter(item -> ApkProcessContract.SELF_SYSTEM_CODE.equals(item.systemCode()))
            .findFirst()
            .orElse(null);
        if (system == null) {
            throw new ServiceException("接入目录缺少系统定义 IWS，请在“系统接入配置 → 系统定义”新增并启用系统码 IWS");
        }
        if (!system.enabled()) {
            throw new ServiceException("接入目录中的系统 IWS 未启用，请在“系统接入配置 → 系统定义”启用该系统");
        }
    }

    private ProvisionedPlan ensureSupervisorDefinition(PlanDefinitionService definitions, String key, String reason) {
        PageResult<DefinitionSummaryVo> versions = definitions.page(
            ApkProcessContract.SUPERVISOR_PLAN_CODE, "ACTIVE", "SUPERVISOR", new PageQuery(PAGE_SIZE, 1));
        List<DefinitionSummaryVo> active = versions.getRows().stream().filter(item ->
            ApkProcessContract.SUPERVISOR_PLAN_CODE.equals(item.planCode())).toList();
        if (active.size() > 1) {
            throw new ServiceException("APK 即时监督生效版本不唯一");
        }
        if (!active.isEmpty()) {
            DefinitionDetailVo detail = definitions.get(parsePositiveLong(active.getFirst().versionId(), "versionId"));
            verifySupervisor(detail);
            return new ProvisionedPlan(parsePositiveLong(detail.summary().versionId(), "versionId"),
                parsePositiveLong(detail.summary().revision(), "revision"), false);
        }
        PageResult<DefinitionSummaryVo> drafts = definitions.page(ApkProcessContract.SUPERVISOR_PLAN_CODE,
            "DRAFT", "SUPERVISOR", new PageQuery(PAGE_SIZE, 1));
        List<DefinitionSummaryVo> matching = drafts.getRows().stream().filter(item ->
            ApkProcessContract.SUPERVISOR_PLAN_CODE.equals(item.planCode())).toList();
        if (matching.size() > 1) {
            throw new ServiceException("APK 即时监督草稿不唯一");
        }
        DefinitionDetailVo draft = matching.isEmpty()
            ? definitions.createDraft(supervisorDraft(), key + ":supervisor-draft", reason)
            : definitions.get(parsePositiveLong(matching.getFirst().versionId(), "versionId"));
        verifySupervisor(draft);
        long id = parsePositiveLong(draft.summary().versionId(), "versionId");
        long revision = parsePositiveLong(draft.summary().revision(), "revision");
        requireValid(definitions.validate(id), "APK 监督计划校验");
        requireValid(definitions.publish(id, revision, key + ":supervisor-publish", reason), "APK 监督计划发布");
        DefinitionDetailVo published = definitions.get(id);
        verifySupervisor(published);
        if (!"ACTIVE".equals(published.summary().status())) {
            throw new ServiceException("APK 监督计划未生效");
        }
        return new ProvisionedPlan(id, parsePositiveLong(published.summary().revision(), "revision"), matching.isEmpty());
    }

    private void verifySupervisor(DefinitionDetailVo detail) {
        if (detail == null || detail.summary() == null
            || !ApkProcessContract.SUPERVISOR_PLAN_CODE.equals(detail.summary().planCode())
            || !"SUPERVISOR".equals(detail.summary().planRole())
            || !ApkProcessContract.PLAN_TYPE.equals(detail.summary().planType())
            || !ApkProcessContract.BUSINESS_CODE.equals(detail.businessScheduleCode())
            || !"IMMEDIATE".equals(detail.mode()) || !Integer.valueOf(1).equals(detail.activePlanLimit())
            || !Long.valueOf(ApkProcessContract.MAX_EXECUTION_MS).equals(detail.maxExecutionMs())
            || detail.roundMaxDurationMs() == null || detail.roundMaxDurationMs() <= 0
            || detail.events() == null || detail.events().size() != 1
            || !SupervisorRules.ACTION_DECISION.equals(detail.events().getFirst().localActionKey())) {
            throw new ServiceException("已存在的 APK 即时监督计划与内置合同冲突，请核查定义");
        }
    }

    private DefinitionDraftBo supervisorDraft() {
        DefinitionStateBo start = new DefinitionStateBo(ApkProcessContract.SUPERVISOR_STATE_START, "监督开始", "START",
            ApkProcessContract.SUPERVISOR_STATE_VALIDATOR_KEY, "1", null, false, 0);
        DefinitionStateBo end = new DefinitionStateBo(ApkProcessContract.SUPERVISOR_STATE_END, "监督结束", "END",
            ApkProcessContract.SUPERVISOR_STATE_VALIDATOR_KEY, "1", null, true, 1);
        DefinitionEventBo decision = new DefinitionEventBo(ApkProcessContract.SUPERVISOR_EVENT_CODE,
            "即时准入监督", "DECISION", "INTERNAL", ApkProcessContract.SUPERVISOR_EVENT_TYPE_KEY, "1",
            null, null, null, SupervisorRules.ACTION_DECISION, "1", null,
            "APK_SUPERVISOR_DECISION", "IWS", "IWS", null, null, null,
            start.stateCode(), end.stateCode(), true, null, null, null, 0,
            "EDITABLE", "EDITABLE_NEW_VERSION", null, "即时请求准入由平台驱动");
        return new DefinitionDraftBo(ApkProcessContract.SUPERVISOR_PLAN_CODE,
            ApkProcessContract.SUPERVISOR_PLAN_NAME, null, ApkProcessContract.PLAN_TYPE,
            ApkProcessContract.BUSINESS_TYPE, ApkProcessContract.BUSINESS_CODE, "IWS",
            "SUPERVISOR", "IMMEDIATE", 1, null, ApkProcessContract.MAX_EXECUTION_MS,
            "ALL_SELECTED", null, 1, ApkProcessContract.MAX_EXECUTION_MS, 1,
            ApkProcessContract.MAX_EXECUTION_MS, ApkProcessContract.LEASE_MS, 0, 0L,
            null, null, null, 0, "APK 即时发布监督", null, null,
            List.of(start, end), List.of(decision), List.of(),
            List.of(new SystemPolicyBo("IWS", "AUTOMATIC", null, null, "本地即时发布")));
    }

    private record ProvisionedPlan(long versionId, long revision, boolean created) { }

    /**
     * {@link #ensurePlan(String, String)} 的同义入口，供上传接线使用。
     *
     * @param commandKey 幂等命令键
     * @param reason 中文操作原因
     * @return 计划版本事实
     */
    public PlanProvisioningResult initialize(String commandKey, String reason) {
        return ensurePlan(commandKey, reason);
    }

    private PlanProvisioningResult ensureDefinition(PlanDefinitionService definitions, String key, String reason) {
        DefinitionDetailVo active = findUnique(definitions, "ACTIVE");
        if (active != null) {
            verifyPlan(active);
            return result(active, false);
        }
        boolean created = false;
        DefinitionDetailVo draft = findUnique(definitions, "DRAFT");
        if (draft == null) {
            draft = definitions.createDraft(definitionDraft(), key + ":draft", reason);
            created = true;
        }
        if (draft == null) {
            throw new ServiceException("APK 发布计划草稿创建失败");
        }
        verifyPlan(draft);
        long versionId = parsePositiveLong(draft.summary().versionId(), "versionId");
        long revision = parsePositiveLong(draft.summary().revision(), "revision");
        requireValid(definitions.validate(versionId), "APK 发布计划校验");
        requireValid(definitions.publish(versionId, revision, key + ":publish", reason),
            "APK 发布计划发布");
        DefinitionDetailVo published = definitions.get(versionId);
        verifyPlan(published);
        if (!"ACTIVE".equals(published.summary().status())) {
            throw new ServiceException("APK 发布计划发布后不是 ACTIVE 状态");
        }
        return result(published, created);
    }

    private BusinessScheduleVo ensureSchedule(BusinessScheduleService schedules, String key, String reason) {
        BusinessScheduleVo existing = findSchedule(schedules);
        if (existing != null) {
            requireScheduleEnabled(existing);
            return existing;
        }
        BusinessScheduleVo created = schedules.create(new BusinessScheduleBo(
            ApkProcessContract.BUSINESS_CODE, ApkProcessContract.PLAN_NAME, true, null,
            "APK 发布计划"), key + ":schedule", reason);
        if (created == null) {
            throw new ServiceException("APK 发布业务调度创建失败");
        }
        return created;
    }

    private void ensurePlanType(BusinessScheduleService schedules, BusinessScheduleVo schedule,
                                String key, String reason) {
        PlanTypeVo existing = planType(schedule);
        if (existing != null) {
            if (!Boolean.TRUE.equals(existing.enabled())) {
                throw new ServiceException("APK_PUBLISH 计划类型已停用");
            }
            return;
        }
        List<PlanTypeBo> types = new ArrayList<>();
        Collection<PlanTypeVo> current = schedule.planTypes();
        if (current != null) {
            for (PlanTypeVo item : current) {
                types.add(new PlanTypeBo(item.planType(), item.planTypeName(), item.priority(), item.enabled()));
            }
        }
        types.add(new PlanTypeBo(ApkProcessContract.PLAN_TYPE, ApkProcessContract.PLAN_NAME, 0, true));
        schedules.savePlanTypes(parsePositiveLong(schedule.id(), "scheduleId"), types,
            parsePositiveLong(schedule.revision(), "schedule.revision"), key + ":plan-type", reason);
    }

    private BusinessScheduleVo findSchedule(BusinessScheduleService schedules) {
        PageResult<BusinessScheduleVo> page = schedules.page(ApkProcessContract.BUSINESS_CODE, null,
            new PageQuery(PAGE_SIZE, 1));
        List<BusinessScheduleVo> matches = page.getRows().stream()
            .filter(item -> ApkProcessContract.BUSINESS_CODE.equals(item.businessCode()))
            .toList();
        if (matches.size() > 1) {
            throw new ServiceException("APK_RELEASE 业务调度不唯一");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private DefinitionDetailVo findUnique(PlanDefinitionService definitions, String status) {
        PageResult<DefinitionSummaryVo> page = definitions.page(ApkProcessContract.PLAN_CODE, status,
            "BUSINESS", new PageQuery(PAGE_SIZE, 1));
        List<DefinitionSummaryVo> matches = page.getRows().stream()
            .filter(item -> ApkProcessContract.PLAN_CODE.equals(item.planCode()))
            .filter(item -> ApkProcessContract.PLAN_TYPE.equals(item.planType()))
            .filter(item -> status.equals(item.status()))
            .toList();
        if (matches.size() > 1) {
            throw new ServiceException("APK 发布计划 " + status + " 版本不唯一");
        }
        if (matches.isEmpty()) {
            return null;
        }
        return definitions.get(parsePositiveLong(matches.get(0).versionId(), "versionId"));
    }

    private void requireScheduleEnabled(BusinessScheduleVo schedule) {
        if (!Boolean.TRUE.equals(schedule.enabled())) {
            throw new ServiceException("APK_RELEASE 业务调度已停用");
        }
    }

    private void requirePlanType(BusinessScheduleVo schedule) {
        PlanTypeVo type = planType(schedule);
        if (type == null || !Boolean.TRUE.equals(type.enabled())) {
            throw new ServiceException("APK_PUBLISH 计划类型未启用");
        }
    }

    private PlanTypeVo planType(BusinessScheduleVo schedule) {
        if (schedule.planTypes() == null) {
            return null;
        }
        return schedule.planTypes().stream()
            .filter(item -> ApkProcessContract.PLAN_TYPE.equals(item.planType()))
            .findFirst().orElse(null);
    }

    private BusinessScheduleVo requireSchedule(BusinessScheduleService schedules) {
        BusinessScheduleVo schedule = findSchedule(schedules);
        if (schedule == null) {
            throw new ServiceException("APK_RELEASE 业务调度尚未初始化");
        }
        requireScheduleEnabled(schedule);
        return schedule;
    }

    private void verifyPlan(DefinitionDetailVo detail) {
        if (detail == null || detail.summary() == null) {
            throw new ServiceException("APK 发布计划详情为空");
        }
        DefinitionSummaryVo summary = detail.summary();
        if (!ApkProcessContract.PLAN_CODE.equals(summary.planCode())
            || !ApkProcessContract.PLAN_TYPE.equals(summary.planType())
            || !"BUSINESS".equals(summary.planRole())) {
            throw new ServiceException("APK 发布计划头与约定不一致");
        }
        if (!ApkProcessContract.SELF_SYSTEM_CODE.equals(detail.selfSystemCode())
            || !ApkProcessContract.BUSINESS_TYPE.equals(detail.businessType())
            || !ApkProcessContract.BUSINESS_CODE.equals(detail.businessScheduleCode())
            || detail.mode() != null || detail.activePlanLimit() != null
            || detail.fairnessWindowSize() != null
            || detail.maxExecutionMs() == null || detail.maxExecutionMs() <= 0
            || !"ALL_SELECTED".equals(detail.completionPolicy())
            || (detail.exceptionStateCode() != null && !detail.exceptionStateCode().isBlank())
            || detail.leaseMs() == null || detail.leaseMs() <= 0
            || (detail.retryMax() != null && detail.retryMax() > 0)) {
            throw new ServiceException("APK 发布计划参数与约定不一致");
        }
        requireState(detail.states(), ApkProcessContract.STATE_START, "START", false);
        requireState(detail.states(), ApkProcessContract.STATE_PUBLISHED, "END", true);
        if (detail.states() == null || detail.states().size() != 2) {
            throw new ServiceException("APK 发布计划状态集合与约定不一致");
        }
        requireEvent(detail.events());
        if (detail.edges() != null && !detail.edges().isEmpty()) {
            throw new ServiceException("APK 发布计划不应包含事件边");
        }
        requirePolicy(detail.systemPolicies());
    }

    private void requireState(List<DefinitionStateBo> states, String code, String kind, boolean terminal) {
        if (states == null) {
            throw new ServiceException("APK 发布计划缺少状态集合");
        }
        DefinitionStateBo state = states.stream().filter(item -> code.equals(item.stateCode()))
            .findFirst().orElse(null);
        if (state == null || !kind.equals(state.stateKind()) || !Boolean.valueOf(terminal).equals(state.terminal())
            || !ApkProcessContract.STATE_VALIDATOR_KEY.equals(state.validatorKey())
            || !ApkProcessContract.VERSION.equals(state.validatorVersion())) {
            throw new ServiceException("APK 发布计划状态与约定不一致: " + code);
        }
    }

    private void requireEvent(List<DefinitionEventBo> events) {
        if (events == null || events.size() != 1) {
            throw new ServiceException("APK 发布计划必须且只能有一个事件");
        }
        DefinitionEventBo event = events.get(0);
        if (!ApkProcessContract.EVENT_CODE.equals(event.eventCode())
            || !"BUSINESS".equals(event.eventRole())
            || !"INTERNAL".equals(event.eventDirection())
            || !ApkProcessContract.EVENT_TYPE_KEY.equals(event.eventTypeKey())
            || !ApkProcessContract.VERSION.equals(event.eventTypeVersion())
            || event.callContractKey() != null || event.callContractVersion() != null
            || event.operationKind() != null || event.effectKey() != null
            || !ApkProcessContract.LOCAL_ACTION_KEY.equals(event.localActionKey())
            || !ApkProcessContract.VERSION.equals(event.localActionVersion())
            || !ApkProcessContract.LOCAL_EFFECT_KEY.equals(event.localEffectKey())
            || !ApkProcessContract.SELF_SYSTEM_CODE.equals(event.systemCode())
            || !ApkProcessContract.SELF_SYSTEM_CODE.equals(event.targetSystemCode())
            || event.endpointRef() != null || event.purpose() != null || event.completionContract() != null
            || !ApkProcessContract.STATE_START.equals(event.sourceStateCode())
            || !ApkProcessContract.STATE_PUBLISHED.equals(event.targetStateCode())
            || !Boolean.TRUE.equals(event.enabled())
            || event.skipPolicy() != null || event.skipContractKey() != null
            || event.recoveryForEventCode() != null) {
            throw new ServiceException("APK 发布事件与约定不一致");
        }
    }

    private void requirePolicy(List<SystemPolicyBo> policies) {
        if (policies == null || policies.size() != 1) {
            throw new ServiceException("APK 发布计划必须且只能有本系统策略");
        }
        SystemPolicyBo policy = policies.get(0);
        if (!ApkProcessContract.SELF_SYSTEM_CODE.equals(policy.systemCode())
            || !"AUTOMATIC".equals(policy.postingMode())) {
            throw new ServiceException("APK 发布计划系统策略与约定不一致");
        }
    }

    private DefinitionDraftBo definitionDraft() {
        return new DefinitionDraftBo(ApkProcessContract.PLAN_CODE, ApkProcessContract.PLAN_NAME, null,
            ApkProcessContract.PLAN_TYPE, ApkProcessContract.BUSINESS_TYPE, ApkProcessContract.BUSINESS_CODE,
            ApkProcessContract.SELF_SYSTEM_CODE, "BUSINESS", null, null, null,
            ApkProcessContract.MAX_EXECUTION_MS, "ALL_SELECTED", null, null, null, null, null,
            ApkProcessContract.LEASE_MS, 0, 0L, null, null, null, 0,
            "APK 上传完成后在本地事务内发布版本", null, null,
            List.of(state(ApkProcessContract.STATE_START, "开始", "START", false, 0),
                state(ApkProcessContract.STATE_PUBLISHED, "已发布", "END", true, 1)),
            List.of(event()), List.of(),
            List.of(new SystemPolicyBo(ApkProcessContract.SELF_SYSTEM_CODE, "AUTOMATIC", null, null,
                "APK 本地发布策略")));
    }

    private DefinitionStateBo state(String code, String name, String kind, boolean terminal, int order) {
        return new DefinitionStateBo(code, name, kind, ApkProcessContract.STATE_VALIDATOR_KEY,
            ApkProcessContract.VERSION, null, terminal, order);
    }

    private DefinitionEventBo event() {
        return new DefinitionEventBo(ApkProcessContract.EVENT_CODE, ApkProcessContract.PLAN_NAME,
            "BUSINESS", "INTERNAL", ApkProcessContract.EVENT_TYPE_KEY, ApkProcessContract.VERSION,
            null, null, null, ApkProcessContract.LOCAL_ACTION_KEY, ApkProcessContract.VERSION, null,
            ApkProcessContract.LOCAL_EFFECT_KEY, ApkProcessContract.SELF_SYSTEM_CODE,
            ApkProcessContract.SELF_SYSTEM_CODE, null, null, null, ApkProcessContract.STATE_START,
            ApkProcessContract.STATE_PUBLISHED, true, null, null, null, 0,
            "EDITABLE", "EDITABLE_NEW_VERSION", null, "本地发布 APK 版本，不发送 HTTP");
    }

    private PlanProvisioningResult result(DefinitionDetailVo detail, boolean created) {
        return new PlanProvisioningResult(parsePositiveLong(detail.summary().versionId(), "versionId"),
            parsePositiveLong(detail.summary().revision(), "revision"),
            ApkProcessContract.PLAN_CODE, ApkProcessContract.PLAN_TYPE, created, true);
    }

    private void requireValid(DefinitionValidationVo validation, String phase) {
        if (validation == null || !validation.valid()) {
            String errors = validation == null || validation.errors() == null ? "无校验结果"
                : String.join("; ", validation.errors());
            throw new ServiceException(phase + "未通过: " + errors);
        }
    }

    private ServiceException notSucceeded(ImmediateExecutionVO result) {
        if (result == null) {
            return new ServiceException("APK 发布未返回执行结果");
        }
        StringBuilder message = new StringBuilder("APK 发布未成功: resultCode=")
            .append(result.resultCode()).append(", effectResult=").append(result.effectResult());
        if (result.instanceId() != null && !result.instanceId().isBlank()) {
            message.append(", instanceId=").append(result.instanceId());
        }
        if (result.message() != null && !result.message().isBlank()) {
            message.append(", message=").append(result.message());
        }
        return new ServiceException(message.append("; 不能按成功处理").toString());
    }

    private void requireProcessEnabled() {
        if (!Boolean.TRUE.equals(environment.getProperty(PROCESS_ENABLED, Boolean.class, Boolean.FALSE))) {
            throw new ServiceException("计划事件平台未启用，拒绝 APK 发布");
        }
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
            long parsed = Long.parseLong(ProcessText.required(value, name));
            if (parsed <= 0) {
                throw new ServiceException(name + " 必须为正整数");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new ServiceException(name + " 必须为数字: " + value);
        }
    }
}
