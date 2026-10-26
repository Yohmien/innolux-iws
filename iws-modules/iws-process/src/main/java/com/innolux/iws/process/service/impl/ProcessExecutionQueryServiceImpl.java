/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划执行总览、请求记录、实例链、事件事实与审计只读实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.query.EventExecutionVO;
import com.innolux.iws.process.api.query.EventRequestVO;
import com.innolux.iws.process.api.query.ExecutionOverviewVO;
import com.innolux.iws.process.api.query.PlanChainVO;
import com.innolux.iws.process.api.query.ProcessAuditEntryVO;
import com.innolux.iws.process.api.query.ProcessAuditQuery;
import com.innolux.iws.process.api.query.ProcessExecutionOverviewQuery;
import com.innolux.iws.process.api.query.ProcessInstanceQueryRow;
import com.innolux.iws.process.api.query.ProcessRequestQuery;
import com.innolux.iws.process.api.query.ProcessRequestRecordRow;
import com.innolux.iws.process.api.query.ProcessRequestRecordVO;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.BusinessSchedulePlanType;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessRequestDetail;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.domain.ProcessTriggerReceipt;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.CheckpointStatus;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.BusinessSchedulePlanTypeMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessExecutionQueryMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessRequestDetailMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.mapper.ProcessStateMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.mapper.ProcessTriggerReceiptMapper;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import com.innolux.iws.process.runtime.PlanCandidateQueryService;
import com.innolux.iws.process.runtime.RuntimeDao;
import com.innolux.iws.process.service.ProcessExecutionQueryService;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.process.support.ProcessRequestQuerySupport;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计划执行只读查询实现。
 *
 * <p>读模型只使用已持久事实和冻结定义快照；不重算并写事件状态，不发送外部请求，不触发命令。
 * 有父对象的查询入口按业务调度/实例/事件授权，再由查询 Mapper 的 {@code @DataPermission} 做 SQL 级过滤；
 * 请求总览对未关联计划的历史记录按其自身来源范围过滤。</p>
 */
@Service
@Transactional(readOnly = true)
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessExecutionQueryServiceImpl implements ProcessExecutionQueryService {

    private static final int MAX_AUDIT_PAGE_SIZE = 200;

    private static final int ALL_CANDIDATES = Integer.MAX_VALUE;

    private static final Set<PlanStatus> TERMINAL_PLAN = Set.of(PlanStatus.COMPLETED,
        PlanStatus.FAILED_FINAL, PlanStatus.CANCELLED);

    private static final Set<PlanStatus> EXCEPTION_PLAN = Set.of(PlanStatus.EXCEPTION_HOLD,
        PlanStatus.MANUAL_PENDING, PlanStatus.PAUSED);

    private static final Set<EventStatus> EXECUTABLE_EVENT = Set.of(EventStatus.PENDING,
        EventStatus.RETRY_WAIT, EventStatus.PROCESSING);

    private static final Set<EventStatus> TERMINAL_EVENT = Set.of(EventStatus.SUCCESS,
        EventStatus.SKIPPED, EventStatus.NOT_SELECTED, EventStatus.FAILED_FINAL);

    private static final Map<PlanStatus, String> PLAN_STATUS_TEXT = Map.of(
        PlanStatus.CREATED, "未准入",
        PlanStatus.RUNNING, "运行中",
        PlanStatus.MANUAL_PENDING, "待人工",
        PlanStatus.EXCEPTION_HOLD, "异常停留",
        PlanStatus.PAUSED, "已暂停",
        PlanStatus.COMPLETED, "已完成",
        PlanStatus.FAILED_FINAL, "最终失败",
        PlanStatus.CANCELLED, "已取消");

    private static final Map<EventStatus, String> EVENT_STATUS_TEXT = Map.of(
        EventStatus.WAIT_PREDECESSOR, "等待前驱",
        EventStatus.WAIT_EXTERNAL_TRIGGER, "等待外部触发",
        EventStatus.MANUAL_PENDING, "待人工",
        EventStatus.PENDING, "待执行",
        EventStatus.PROCESSING, "执行中",
        EventStatus.RETRY_WAIT, "待重试",
        EventStatus.SUCCESS, "成功",
        EventStatus.SKIPPED, "已跳过",
        EventStatus.NOT_SELECTED, "未选中",
        EventStatus.FAILED_FINAL, "最终失败");

    private static final Map<String, String> ACTION_TEXT = Map.ofEntries(
        Map.entry("DEFINITION_CREATE", "计划草稿新增"),
        Map.entry("DEFINITION_UPDATE", "计划草稿修改"),
        Map.entry("DEFINITION_PUBLISH", "计划发布"),
        Map.entry("DEFINITION_COPY", "计划复制"),
        Map.entry("DEFINITION_DELETE", "计划草稿删除"),
        Map.entry("ATTACH", "任务挂载"),
        Map.entry("TRIGGER", "触发受理"),
        Map.entry("CONFIRM_POSTING", "首次确认"),
        Map.entry("EVENT_RETRY", "事件重试"),
        Map.entry("EVENT_FINALIZE", "事件最终失败"),
        Map.entry("EVENT_REVALIDATE", "事件重新校验"),
        Map.entry("EVENT_EXECUTE", "事件执行"),
        Map.entry("EVENT_SKIP", "事件跳过"),
        Map.entry("PLAN_PAUSE", "计划暂停"),
        Map.entry("PLAN_RESUME", "计划恢复"),
        Map.entry("PLAN_CANCEL", "计划取消"),
        Map.entry("EVENT_PRIORITY", "事件优先级"),
        Map.entry("SCHEDULE_CONTROL", "调度控制"),
        Map.entry("COMMAND_REJECTED", "命令拒绝"),
        Map.entry("APK_DOWNLOAD", "APK 下载"));

    private static final Map<AuditResult, String> AUDIT_RESULT_TEXT = Map.of(
        AuditResult.SUCCESS, "成功",
        AuditResult.REJECTED, "拒绝",
        AuditResult.FAILED, "失败",
        AuditResult.PARTIAL, "部分成功",
        AuditResult.UNKNOWN, "未知");

    private static final Map<String, String> REQUEST_DIRECTION_TEXT = Map.of(
        "INBOUND", "入站",
        "OUTBOUND", "出站",
        "INTERNAL", "仅本地处理");

    private static final Map<String, String> REQUEST_PURPOSE_TEXT = Map.of(
        "TRIGGER_EVENT", "触发事件",
        "EXECUTE_ACTION", "执行业务动作",
        "RESULT_CALLBACK", "结果回调",
        "QUERY", "查询",
        "APK_DOWNLOAD", "APK 下载",
        "CONTROL", "控制");

    private static final Map<String, String> EFFECT_RESULT_TEXT = Map.of(
        "SUCCEEDED", "成功",
        "ACCEPTED", "已受理",
        "NO_EFFECT", "无效果",
        "SKIPPED", "已跳过",
        "SKIPPED_MODE", "已屏蔽",
        "UNKNOWN", "结果未知",
        "FAILED", "失败");

    private static final Map<String, String> ASSOCIATION_TEXT = Map.of(
        "LINKED", "已关联计划",
        "UNLINKED", "未关联计划",
        "UNKNOWN", "关联未确认");

    private final ProcessExecutionQueryMapper queryMapper;

    private final ProcessObjectAuthorizer objectAuthorizer;

    private final BusinessScheduleMapper scheduleMapper;

    private final BusinessSchedulePlanTypeMapper planTypeMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessStateMapper stateMapper;

    private final ProcessTokenMapper tokenMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final ProcessRequestDetailMapper requestDetailMapper;

    private final ProcessTriggerReceiptMapper receiptMapper;

    private final ProcessSystemContextMapper contextMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final RuntimeDao runtimeDao;
    private final com.innolux.iws.process.runtime.QueryRecoveryAdmission recoveryAdmission;
    private final com.innolux.iws.process.runtime.PlanInstanceActionPolicy instanceActionPolicy;

    private final PlanCandidateQueryService candidateQueryService;

    private final ProcessRequestQuerySupport requestQuerySupport;

    /**
     * 构造只读查询服务。
     *
     * @param queryMapper 数据范围查询 Mapper
     * @param objectAuthorizer 对象范围授权
     * @param scheduleMapper 业务调度 Mapper
     * @param planTypeMapper 计划类型 Mapper
     * @param definitionMapper 计划定义 Mapper
     * @param instanceMapper 实例 Mapper
     * @param stateMapper 状态 Mapper
     * @param tokenMapper token Mapper
     * @param eventMapper 事件 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param requestDetailMapper 请求明细 Mapper
     * @param receiptMapper 入站回执 Mapper
     * @param contextMapper 系统上下文 Mapper
     * @param controlMapper 调度控制 Mapper
     * @param runtimeDao 只读图装载
     * @param candidateQueryService worker 与查询共享的 QUEUE 候选来源
     * @param requestQuerySupport 请求记录来源与对象范围查询
     */
    public ProcessExecutionQueryServiceImpl(ProcessExecutionQueryMapper queryMapper,
                                            ProcessObjectAuthorizer objectAuthorizer,
                                            BusinessScheduleMapper scheduleMapper,
                                            BusinessSchedulePlanTypeMapper planTypeMapper,
                                            PlanDefinitionMapper definitionMapper,
                                            ProcessInstanceMapper instanceMapper,
                                            ProcessStateMapper stateMapper,
                                            ProcessTokenMapper tokenMapper,
                                            ProcessEventMapper eventMapper,
                                            ProcessAttemptMapper attemptMapper,
                                            ProcessRequestDetailMapper requestDetailMapper,
                                            ProcessTriggerReceiptMapper receiptMapper,
                                            ProcessSystemContextMapper contextMapper,
                                            ProcessScheduleControlMapper controlMapper,
                                            RuntimeDao runtimeDao,
                                            PlanCandidateQueryService candidateQueryService,
                                            ProcessRequestQuerySupport requestQuerySupport,
                                            com.innolux.iws.process.runtime.PlanInstanceActionPolicy instanceActionPolicy,
                                            com.innolux.iws.process.runtime.QueryRecoveryAdmission recoveryAdmission) {
        this.queryMapper = queryMapper;
        this.objectAuthorizer = objectAuthorizer;
        this.scheduleMapper = scheduleMapper;
        this.planTypeMapper = planTypeMapper;
        this.definitionMapper = definitionMapper;
        this.instanceMapper = instanceMapper;
        this.stateMapper = stateMapper;
        this.tokenMapper = tokenMapper;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.requestDetailMapper = requestDetailMapper;
        this.receiptMapper = receiptMapper;
        this.contextMapper = contextMapper;
        this.controlMapper = controlMapper;
        this.runtimeDao = runtimeDao;
        this.candidateQueryService = candidateQueryService;
        this.requestQuerySupport = requestQuerySupport;
        this.instanceActionPolicy = instanceActionPolicy;
        this.recoveryAdmission = recoveryAdmission;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExecutionOverviewVO overview(ProcessExecutionOverviewQuery query) {
        LocalDateTime asOf = LocalDateTime.now();
        String scheduleCode = ProcessText.required(query == null ? null : query.businessScheduleCode(),
            "businessScheduleCode");
        BusinessSchedule schedule = scheduleMapper.selectOne(Wrappers.<BusinessSchedule>lambdaQuery()
            .eq(BusinessSchedule::getBusinessCode, scheduleCode)
            .last("LIMIT 1"));
        if (schedule == null) {
            throw new ServiceException("业务调度不存在: " + scheduleCode);
        }
        objectAuthorizer.authorizeSchedule(schedule.getId(), "QUERY");
        String businessId = blankToNull(query.businessId());
        String taskKey = blankToNull(query.taskKey());
        String planType = blankToNull(query.planType());
        String instanceId = blankToNull(query.instanceId());
        Page<ProcessInstance> executedPage = queryMapper.selectExecutedOverviewPage(
            page(query.executedPage()), scheduleCode, businessId, taskKey, planType, instanceId);
        Page<ProcessInstanceQueryRow> pendingPage = queryMapper.selectPendingOverviewPage(
            page(query.pendingPage()), scheduleCode, businessId, taskKey, planType, instanceId);
        Page<ProcessInstance> runningPage = queryMapper.selectRunningOverviewPage(
            page(query.runningPage()), scheduleCode, businessId, taskKey, planType, instanceId);
        Page<ProcessInstance> exceptionPage = queryMapper.selectExceptionOverviewPage(
            page(query.exceptionPage()), scheduleCode, businessId, taskKey, planType, instanceId);
        List<ProcessInstance> pageRows = new ArrayList<>();
        pageRows.addAll(executedPage.getRecords());
        pageRows.addAll(pendingPage.getRecords());
        pageRows.addAll(runningPage.getRecords());
        pageRows.addAll(exceptionPage.getRecords());
        Map<String, PlanTypeInfo> typeInfo = planTypes(schedule.getId());
        Map<Long, PlanDefinition> definitions = definitions(pageRows);
        Map<Long, DefinitionGraph> graphs = graphs(definitions.keySet());
        Map<Long, List<ProcessToken>> tokens = tokens(pageRows);
        Map<Long, List<ProcessEvent>> events = events(pageRows);
        Map<Long, Integer> eventRanks = eventRanks(schedule.getId(), events.values().stream()
            .flatMap(List::stream).toList());
        Map<Long, PendingRank> pendingRanks = pendingStatuses(schedule, pendingPage.getRecords());

        List<ExecutionOverviewVO.ExecutionPlanVO> executed = new ArrayList<>();
        for (ProcessInstance instance : executedPage.getRecords()) {
            PlanDefinition definition = definitions.get(instance.getDefinitionVersionId());
            DefinitionGraph graph = graphs.get(instance.getDefinitionVersionId());
            PlanTypeInfo type = typeInfo.getOrDefault(instance.getPlanType(),
                new PlanTypeInfo(0, instance.getPlanType()));
            List<ProcessEvent> instanceEvents = events.getOrDefault(instance.getId(), List.of());
            List<ProcessToken> instanceTokens = tokens.getOrDefault(instance.getId(), List.of());
            executed.add(summary(instance, definition, graph, type, instanceEvents, instanceTokens, null,
                eventRanks));
        }
        List<ExecutionOverviewVO.ExecutionPlanVO> pending = new ArrayList<>();
        for (ProcessInstance instance : pendingPage.getRecords()) {
            PlanDefinition definition = definitions.get(instance.getDefinitionVersionId());
            DefinitionGraph graph = graphs.get(instance.getDefinitionVersionId());
            PlanTypeInfo type = typeInfo.getOrDefault(instance.getPlanType(),
                new PlanTypeInfo(0, instance.getPlanType()));
            List<ProcessEvent> instanceEvents = events.getOrDefault(instance.getId(), List.of());
            List<ProcessToken> instanceTokens = tokens.getOrDefault(instance.getId(), List.of());
            pending.add(summary(instance, definition, graph, type, instanceEvents, instanceTokens,
                pendingRanks.get(instance.getId()), eventRanks));
        }
        List<ExecutionOverviewVO.ExecutionPlanVO> running = new ArrayList<>();
        for (ProcessInstance instance : runningPage.getRecords()) {
            PlanDefinition definition = definitions.get(instance.getDefinitionVersionId());
            DefinitionGraph graph = graphs.get(instance.getDefinitionVersionId());
            PlanTypeInfo type = typeInfo.getOrDefault(instance.getPlanType(),
                new PlanTypeInfo(0, instance.getPlanType()));
            running.add(summary(instance, definition, graph, type,
                events.getOrDefault(instance.getId(), List.of()),
                tokens.getOrDefault(instance.getId(), List.of()), null, eventRanks));
        }
        List<ExecutionOverviewVO.ExecutionPlanVO> exception = new ArrayList<>();
        for (ProcessInstance instance : exceptionPage.getRecords()) {
            PlanDefinition definition = definitions.get(instance.getDefinitionVersionId());
            DefinitionGraph graph = graphs.get(instance.getDefinitionVersionId());
            PlanTypeInfo type = typeInfo.getOrDefault(instance.getPlanType(),
                new PlanTypeInfo(0, instance.getPlanType()));
            exception.add(summary(instance, definition, graph, type,
                events.getOrDefault(instance.getId(), List.of()),
                tokens.getOrDefault(instance.getId(), List.of()), null, eventRanks));
        }
        return new ExecutionOverviewVO(asOf.toString(), schedule.getBusinessCode(), schedule.getBusinessName(),
            ProcessText.id(schedule.getRevision()),
            "四类均由同一数据范围 SQL 分页和同 scope 总数；未准入位次由数据库按监督候选顺序计算",
            PageResult.build(executed, executedPage.getTotal()),
            PageResult.build(pending, pendingPage.getTotal()),
            PageResult.build(running, runningPage.getTotal()),
            PageResult.build(exception, exceptionPage.getTotal()),
            new ExecutionOverviewVO.OverviewTotals(executedPage.getTotal(), pendingPage.getTotal(),
                runningPage.getTotal(), exceptionPage.getTotal()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<ProcessRequestRecordVO> requestPage(ProcessRequestQuery query) {
        ProcessRequestQuery normalized = normalizeRequestQuery(query);
        authorizeRequestScope(normalized);
        Page<ProcessRequestRecordRow> result = requestQuerySupport.selectPage(normalized);
        return PageResult.build(result.getRecords().stream().map(this::requestRecordView).toList(),
            result.getTotal());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProcessRequestRecordVO requestRecord(String requestId) {
        if (blank(requestId)) {
            throw new ServiceException("requestId 不能为空");
        }
        ProcessRequestRecordRow row = requestQuerySupport.selectVisibleRecord(requestId.trim());
        if (row == null) {
            throw new ServiceException("请求记录不存在或无权访问: " + requestId);
        }
        return requestRecordView(row);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlanChainVO chain(long instanceId) {
        LocalDateTime asOf = LocalDateTime.now();
        ProcessInstance instance = requireVisibleInstance(instanceId);
        objectAuthorizer.authorizeInstance(instanceId, "QUERY");
        PlanDefinition definition = requireDefinition(instance.getDefinitionVersionId());
        DefinitionGraph graph = runtimeDao.graph(instance.getDefinitionVersionId());
        List<ProcessState> states = stateMapper.selectList(Wrappers.<ProcessState>lambdaQuery()
            .eq(ProcessState::getDefinitionVersionId, instance.getDefinitionVersionId())
            .orderByAsc(ProcessState::getSortOrder));
        List<ProcessToken> tokens = tokenMapper.selectList(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instanceId).orderByAsc(ProcessToken::getId));
        List<ProcessEvent> events = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, instanceId).orderByAsc(ProcessEvent::getId));
        Map<Long, List<ProcessAttempt>> attempts = attempts(events);
        List<ProcessSystemContext> contexts = contextMapper.selectList(
            Wrappers.<ProcessSystemContext>lambdaQuery()
                .eq(ProcessSystemContext::getInstanceId, instanceId)
                .orderByAsc(ProcessSystemContext::getSystemCode));
        Map<Long, Integer> eventRanks = eventRanks(instance.getScheduleId(), events);
        RuntimeSnapshot snapshot = new RuntimeSnapshot(instance, definition, graph, states, tokens, events,
            attempts, contexts, eventRanks, asOf);
        PendingRank rank = pendingRank(instance);
        List<PlanChainVO.StateNodeVO> nodes = stateNodes(snapshot);
        List<PlanChainVO.EventChainEdgeVO> edges = eventEdges(snapshot);
        List<PlanChainVO.BranchChainVO> branches = branches(snapshot);
        var admission = instanceActionPolicy.describe(instance, events, contexts);
        return new PlanChainVO(ProcessText.id(instance.getId()), ProcessText.id(instance.getId()),
            definition.getPlanCode(), definition.getPlanName(), String.valueOf(definition.getPlanVersion()),
            instance.getTaskKey(), instance.getPlanStatus() == null ? null : instance.getPlanStatus().name(),
            ProcessText.id(instance.getInstanceVersion()), priority(snapshot), systemContexts(snapshot),
            asOf.toString(), new PlanChainVO.GraphVO(nodes, edges), branches, exception(snapshot),
            rank == null ? null : rank.queueRank(), rank == null ? null : rank.resourceScope(),
            rank == null ? null : rank.eligible(), rank == null ? null : rank.blockReason(),
            rank == null ? null : rank.queueStatus(), instance.getHoldStart(), instance.getBusinessId(),
            instance.getPlanType(), ProcessText.id(instance.getSupervisorInstanceId()),
            instance.getScheduleScope(), time(instance.getCreateTime()), time(instance.getUpdateTime()),
            time(instance.getEndedAt()), admission.allowedActionCodes(), admission.reasons());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventExecutionVO event(long eventId) {
        LocalDateTime asOf = LocalDateTime.now();
        ProcessEvent event = requireVisibleEvent(eventId);
        objectAuthorizer.authorizeEvent(eventId, "QUERY");
        ProcessInstance instance = instanceMapper.selectById(event.getInstanceId());
        if (instance == null) {
            throw new ServiceException("计划实例不存在: " + event.getInstanceId());
        }
        PlanDefinition definition = definitionMapper.selectById(event.getDefinitionVersionId());
        ProcessEventDefinition eventDefinition = definition == null ? null
            : runtimeDao.graph(event.getDefinitionVersionId()).event(event.getEventCode());
        List<ProcessAttempt> attemptList = attemptMapper.selectList(Wrappers.<ProcessAttempt>lambdaQuery()
            .eq(ProcessAttempt::getEventId, eventId).orderByDesc(ProcessAttempt::getAttemptNo));
        ProcessAttempt lastAttempt = attemptList.isEmpty() ? null : attemptList.getFirst();
        ProcessSystemContext context = contextMapper.selectOne(Wrappers.<ProcessSystemContext>lambdaQuery()
            .eq(ProcessSystemContext::getInstanceId, event.getInstanceId())
            .eq(ProcessSystemContext::getSystemCode, event.getSystemCode())
            .last("LIMIT 1"));
        ProcessTriggerReceipt receipt = receiptMapper.selectOne(
            Wrappers.<ProcessTriggerReceipt>lambdaQuery()
                .eq(ProcessTriggerReceipt::getEventId, eventId)
                .orderByDesc(ProcessTriggerReceipt::getId).last("LIMIT 1"));
        ProcessRequestDetail latestRequest = requestDetailMapper.selectOne(
            Wrappers.<ProcessRequestDetail>lambdaQuery()
                .eq(ProcessRequestDetail::getEventId, eventId)
                .orderByDesc(ProcessRequestDetail::getId).last("LIMIT 1"));
        List<Long> related = relatedEventIds(event);
        boolean unknown = unknown(event, lastAttempt);
        var recovery = unknown ? recoveryAdmission.evaluate(event, lastAttempt) : null;
        boolean canRecover = recovery != null && recovery.allowed();
        boolean successfulEffect = lastAttempt != null && lastAttempt.getEffectResult() != null
            && Set.of(EffectResult.SUCCEEDED, EffectResult.ACCEPTED,
            EffectResult.SKIPPED, EffectResult.SKIPPED_MODE).contains(lastAttempt.getEffectResult());
        boolean reusableSuccess = successfulEffect
            && com.innolux.iws.process.runtime.RecoveryPolicy.decide(lastAttempt, Boolean.TRUE.equals(event.getSent()))
            == com.innolux.iws.process.runtime.RecoveryPolicy.Decision.COMPLETE;
        boolean canRetry = canRecover || (!unknown && (event.getEventStatus() == EventStatus.MANUAL_PENDING
            || event.getEventStatus() == EventStatus.RETRY_WAIT
            || (reusableSuccess && event.getEventStatus() == EventStatus.PENDING))
            && (!successfulEffect || reusableSuccess));
        boolean canFinalize = !unknown && canRetry && !successfulEffect;
        String finalizeReason = successfulEffect ? "已有成功检查点，只能恢复编排，不能最终失败"
            : actionReason(event, unknown);
        boolean canRevalidate = event.getEventStatus() == EventStatus.WAIT_PREDECESSOR
            || event.getEventStatus() == EventStatus.WAIT_EXTERNAL_TRIGGER
            || event.getEventStatus() == EventStatus.MANUAL_PENDING
            || event.getEventStatus() == EventStatus.RETRY_WAIT;
        Map<String, EventExecutionVO.ActionDecisionVO> allowedActions = new LinkedHashMap<>();
        allowedActions.put("RETRY", new EventExecutionVO.ActionDecisionVO(canRetry,
            canRetry ? (canRecover ? "仅允许显式对账恢复" : null)
                : recovery == null ? actionReason(event, unknown) : recovery.reason()));
        allowedActions.put("REVALIDATE", new EventExecutionVO.ActionDecisionVO(canRevalidate,
            canRevalidate ? null : actionReason(event, unknown)));
        allowedActions.put("FINALIZE", new EventExecutionVO.ActionDecisionVO(canFinalize,
            canFinalize ? null : finalizeReason));
        Map<String, String> reasons = new LinkedHashMap<>();
        if (!canRetry) {
            reasons.put("RETRY", recovery == null ? actionReason(event, unknown) : recovery.reason());
        }
        if (!canRevalidate) {
            reasons.put("REVALIDATE", actionReason(event, unknown));
        }
        if (!canFinalize) {
            reasons.put("FINALIZE", finalizeReason);
        }
        String displayStatus = eventStatus(event);
        List<PlanChainVO.ValidationVO> validations = new ArrayList<>();
        if (eventDefinition != null) {
            ProcessState source = runtimeDao.graph(event.getDefinitionVersionId())
                .state(eventDefinition.getSourceStateCode());
            ProcessState target = runtimeDao.graph(event.getDefinitionVersionId())
                .state(eventDefinition.getTargetStateCode());
            if (source != null) {
                validations.add(validation(source, List.of(event),
                    lastAttempt == null ? List.of() : List.of(lastAttempt),
                    context == null || context.getPostingMode() == null ? null
                        : context.getPostingMode().name()));
            }
            if (target != null) {
                validations.add(validation(target, List.of(event),
                    lastAttempt == null ? List.of() : List.of(lastAttempt),
                    context == null || context.getPostingMode() == null ? null
                        : context.getPostingMode().name()));
            }
        }
        List<EventExecutionVO.RequestRetryVO> retries = attemptList.stream()
            .filter(item -> item.getRequestId() != null)
            .map(item -> new EventExecutionVO.RequestRetryVO(item.getAttemptNo(), item.getRequestId(),
                time(item.getRecordedAt() == null ? item.getEndedAt() : item.getRecordedAt()),
                item.getCheckpointStatus() == null ? null : item.getCheckpointStatus().name()))
            .toList();
        EventExecutionVO.HttpContractVO contract = eventDefinition == null ? null
            : new EventExecutionVO.HttpContractVO(null, eventDefinition.getTargetSystemCode(),
                eventDefinition.getEndpointRef(), null,
                eventDefinition.getCompletionContract() == null ? null
                    : eventDefinition.getCompletionContract().name());
        return new EventExecutionVO(ProcessText.id(event.getId()), ProcessText.id(event.getEventVersion()),
            event.getEventCode(), eventDefinition == null ? null : eventDefinition.getDisplayName(),
            event.getEventDirection() == null ? null : event.getEventDirection().name(),
            event.getEventRole() == null ? null : event.getEventRole().name(),
            eventDefinition == null ? null : eventDefinition.getEventTypeKey(),
            eventDefinition == null ? null : eventDefinition.getEventTypeVersion(),
            event.getCallContractKey(), event.getCallContractVersion(), event.getLocalActionKey(),
            event.getLocalActionVersion(), event.getSystemCode(), event.getSystemCode(),
            context == null || context.getPostingMode() == null ? null : context.getPostingMode().name(),
            event.getSourceStateCode(), event.getTargetStateCode(), displayStatus, event.getOutcome(),
            event.getSent(), lastAttempt == null || lastAttempt.getCheckpointStatus() == null ? null
                : lastAttempt.getCheckpointStatus().name(),
            lastAttempt == null ? null : lastAttempt.getAttemptNo(), blockedReason(event, lastAttempt),
            event.getWaitReason() == null ? null : event.getWaitReason().name(), null,
            time(Boolean.TRUE.equals(event.getRecoveryRequested()) ? event.getRecoveryDeadline()
                : instance.getExecutionDeadline()),
            latestRequest == null ? null : latestRequest.getCorrelationId(),
            receipt == null ? null : receipt.getExternalTaskId(), receipt == null ? null : ProcessText.id(receipt.getId()),
            ProcessText.id(event.getOriginOutboundEventId()),
            related.stream().map(ProcessText::id).toList(), contract, validations, retries,
            allowedActions, reasons, canRetry, canRevalidate, canFinalize, asOf.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventRequestVO request(long eventId, String requestId) {
        ProcessEvent event = requireVisibleEvent(eventId);
        objectAuthorizer.authorizeEvent(eventId, "QUERY");
        if (requestId == null || requestId.isBlank()) {
            throw new ServiceException("requestId 不能为空");
        }
        ProcessRequestDetail detail = requestDetailMapper.selectOne(
            Wrappers.<ProcessRequestDetail>lambdaQuery()
                .eq(ProcessRequestDetail::getEventId, eventId)
                .eq(ProcessRequestDetail::getRequestId, requestId)
                .orderByDesc(ProcessRequestDetail::getId).last("LIMIT 1"));
        if (detail == null) {
            throw new ServiceException("请求明细不存在或不属于该事件");
        }
        return new EventRequestVO(detail.getRequestId(),
            detail.getDirection() == null ? null : detail.getDirection().name(),
            detail.getPurpose() == null ? null : detail.getPurpose().name(), null, null,
            detail.getHttpStatus(), detail.getRequestSummary(), detail.getResponseSummary(), Boolean.TRUE,
            detail.getEffectResult() == null ? null : detail.getEffectResult().name(), detail.getSent(),
            detail.getCorrelationId(), time(detail.getEvidenceAt() == null ? detail.getEndedAt()
                : detail.getEvidenceAt()), ProcessText.id(detail.getInstanceId()),
            ProcessText.id(detail.getEventId()), detail.getContractKey());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<ProcessAuditEntryVO> audit(ProcessAuditQuery query) {
        if (query == null || (blank(query.businessScheduleCode()) && blank(query.instanceId())
            && blank(query.eventId()))) {
            throw new ServiceException("审计查询必须指定业务调度、实例或事件范围");
        }
        ProcessAuditQuery normalized = normalize(query);
        if (!blank(normalized.instanceId())) {
            objectAuthorizer.authorizeInstance(parseId(normalized.instanceId(), "instanceId"), "QUERY");
        } else if (!blank(normalized.eventId())) {
            objectAuthorizer.authorizeEvent(parseId(normalized.eventId(), "eventId"), "QUERY");
        } else {
            BusinessSchedule schedule = scheduleMapper.selectOne(Wrappers.<BusinessSchedule>lambdaQuery()
                .eq(BusinessSchedule::getBusinessCode, normalized.businessScheduleCode().trim())
                .last("LIMIT 1"));
            if (schedule == null) {
                throw new ServiceException("业务调度不存在: " + normalized.businessScheduleCode());
            }
            objectAuthorizer.authorizeSchedule(schedule.getId(), "QUERY");
        }
        Page<ProcessActionAudit> page = new Page<>(normalized.normalizedPageNum(),
            Math.min(normalized.normalizedPageSize(), MAX_AUDIT_PAGE_SIZE));
        Page<ProcessActionAudit> result = queryMapper.selectAuditPage(page, normalized);
        return PageResult.build(result.getRecords().stream().map(this::auditEntry).toList(),
            result.getTotal());
    }

    private ExecutionOverviewVO.ExecutionPlanVO summary(ProcessInstance instance, PlanDefinition definition,
                                                         DefinitionGraph graph, PlanTypeInfo type,
                                                         List<ProcessEvent> events, List<ProcessToken> tokens,
                                                         PendingRank rank, Map<Long, Integer> eventRanks) {
        ProcessEvent lastEvent = events.stream().max(eventComparator()).orElse(null);
        ProcessEvent current = currentEvent(tokens, events);
        String executionStatus = current == null ? null : eventStatus(current);
        PlanStatus status = instance.getPlanStatus();
        boolean terminal = status != null && TERMINAL_PLAN.contains(status);
        String queueStatus = rank == null ? null : rank.queueStatus();
        Boolean eligible = rank == null ? null : rank.eligible();
        String blockReason = rank == null ? null : rank.blockReason();
        Integer queueRank = rank == null ? null : rank.queueRank();
        String resourceScope = rank == null ? instance.getScheduleScope() : rank.resourceScope();
        var admission = instanceActionPolicy.describe(instance, events, contextMapper.selectList(
            Wrappers.<ProcessSystemContext>lambdaQuery().eq(ProcessSystemContext::getInstanceId, instance.getId())));
        return new ExecutionOverviewVO.ExecutionPlanVO(ProcessText.id(instance.getId()),
            ProcessText.id(instance.getInstanceVersion()), definition == null ? instance.getTaskKey()
                : definition.getPlanName(), definition == null ? null : definition.getPlanCode(),
            definition == null ? null : String.valueOf(definition.getPlanVersion()), instance.getTaskKey(),
            instance.getBusinessId(), instance.getPlanType(), type.name(),
            new ExecutionOverviewVO.PriorityVO(type.priority(), intValue(instance.getInstancePriority()),
                maxEventPriority(events)),
            status == null ? null : status.name(), status == null ? null : PLAN_STATUS_TEXT.get(status),
            time(instance.getStartedAt()), time(instance.getEndedAt()), time(instance.getUpdateTime()),
            terminal && status != null ? status.name() : null,
            terminal && status == PlanStatus.COMPLETED ? "计划已完成"
                : terminal && status == PlanStatus.FAILED_FINAL ? "计划最终失败"
                : terminal && status == PlanStatus.CANCELLED ? "计划已取消" : instance.getExceptionSummary(),
            lastEvent == null ? null : eventName(graph, lastEvent.getEventCode()),
            lastEvent == null ? null : lastEvent.getEventCode(),
            lastEvent == null ? null : eventStatus(lastEvent), exceptionState(instance, tokens, events),
            blockedReason(instance, current),
            queueRank, resourceScope, eligible, blockReason, time(instance.getCreateTime()),
            instance.getHoldStart(), queueStatus, branchSummaries(tokens, events, graph, eventRanks),
            ProcessText.id(instance.getSupervisorInstanceId()), instance.getScheduleScope(),
            terminal ? time(instance.getEndedAt()) : null, admission.allowedActionCodes(), admission.reasons());
    }

    private List<ExecutionOverviewVO.BranchSummaryVO> branchSummaries(List<ProcessToken> tokens,
                                                                      List<ProcessEvent> events,
                                                                      DefinitionGraph graph,
                                                                      Map<Long, Integer> eventRanks) {
        List<ExecutionOverviewVO.BranchSummaryVO> result = new ArrayList<>();
        for (ProcessToken token : tokens) {
            List<ProcessEvent> tokenEvents = events.stream()
                .filter(item -> Objects.equals(item.getTokenId(), token.getId())).toList();
            ProcessEvent current = currentEvent(tokenEvents);
            ProcessState state = graph == null ? null : graph.state(token.getStateCode());
            result.add(new ExecutionOverviewVO.BranchSummaryVO(ProcessText.id(token.getId()),
                token.getBranchKey(), token.getBranchKey(), state == null ? null
                    : new PlanChainVO.StateNodeVO(state.getStateCode(), state.getDisplayName(),
                        state.getConditionDescription(), state.getStateKind() == null ? null
                            : state.getStateKind().name(),
                        state.getTerminal(), Boolean.TRUE, time(token.getUpdateTime()), Boolean.TRUE,
                        List.of(), null, null, null),
                current == null ? null : eventEdge(graph, current, eventRanks),
                current == null ? null : eventName(graph, current.getEventCode()),
                current == null ? null : current.getEventCode(),
                current == null || current.getEventDirection() == null ? null
                    : current.getEventDirection().name(),
                current == null ? null : eventStatus(current),
                current == null ? null : attemptNo(current),
                current == null ? null : ProcessText.id(current.getLeaseEpoch()),
                current == null ? null : time(current.getStartedAt())));
        }
        return result;
    }

    private List<PlanChainVO.StateNodeVO> stateNodes(RuntimeSnapshot snapshot) {
        List<PlanChainVO.StateNodeVO> result = new ArrayList<>();
        for (ProcessState state : snapshot.states()) {
            boolean current = snapshot.tokens().stream()
                .anyMatch(item -> Objects.equals(item.getStateCode(), state.getStateCode())
                    && (item.getTokenStatus() == TokenStatus.ACTIVE
                    || item.getTokenStatus() == TokenStatus.BLOCKED));
            ProcessEvent reachedEvent = snapshot.events().stream()
                .filter(item -> Objects.equals(item.getTargetStateCode(), state.getStateCode()))
                .filter(item -> item.getEventStatus() == EventStatus.SUCCESS
                    || item.getEventStatus() == EventStatus.SKIPPED)
                .max(eventComparator()).orElse(null);
            ProcessToken token = snapshot.tokens().stream()
                .filter(item -> Objects.equals(item.getStateCode(), state.getStateCode()))
                .max(Comparator.comparing(ProcessToken::getUpdateTime,
                    Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
            boolean reached = reachedEvent != null || token != null;
            result.add(new PlanChainVO.StateNodeVO(state.getStateCode(), state.getDisplayName(),
                state.getConditionDescription(), state.getStateKind() == null ? null
                    : state.getStateKind().name(), state.getTerminal(), reached,
                reachedEvent == null ? (token == null ? null : time(token.getUpdateTime()))
                    : time(reachedEvent.getEndedAt() == null ? reachedEvent.getUpdateTime()
                        : reachedEvent.getEndedAt()),
                current, List.of(validation(state, snapshot.events(), attemptsFor(snapshot, state),
                    modeFor(snapshot, state))), null, null, null));
        }
        return result;
    }

    private List<PlanChainVO.EventChainEdgeVO> eventEdges(RuntimeSnapshot snapshot) {
        List<PlanChainVO.EventChainEdgeVO> result = new ArrayList<>();
        for (String code : snapshot.graph().eventCodes()) {
            ProcessEventDefinition definition = snapshot.graph().event(code);
            ProcessEvent event = snapshot.events().stream()
                .filter(item -> Objects.equals(item.getEventCode(), code))
                .max(eventComparator()).orElse(null);
            if (event == null) {
                result.add(new PlanChainVO.EventChainEdgeVO(null, null, code,
                    definition == null ? null : definition.getDisplayName(),
                    definition == null || definition.getEventDirection() == null ? null
                        : definition.getEventDirection().name(),
                    definition == null || definition.getEventRole() == null ? null
                        : definition.getEventRole().name(),
                    definition == null ? null : definition.getEventTypeKey(),
                    definition == null ? null : definition.getEventTypeVersion(),
                    definition == null ? null : definition.getCallContractKey(),
                    definition == null ? null : definition.getCallContractVersion(),
                    definition == null ? null : definition.getLocalActionKey(),
                    definition == null ? null : definition.getLocalActionVersion(),
                    definition == null ? null : definition.getSystemCode(), null,
                    definition == null ? null : definition.getSourceStateCode(),
                    definition == null ? null : definition.getTargetStateCode(),
                    "NOT_EVALUATED", null, null, null, List.of(), null, null, Boolean.FALSE));
                continue;
            }
            result.add(eventEdge(snapshot.graph(), event, snapshot.eventRanks()));
        }
        return result;
    }

    private PlanChainVO.EventChainEdgeVO eventEdge(DefinitionGraph graph, ProcessEvent event,
                                                   Map<Long, Integer> eventRanks) {
        ProcessEventDefinition definition = graph == null ? null : graph.event(event.getEventCode());
        ProcessAttempt attempt = event.getLastAttemptId() == null ? null
            : attemptMapper.selectById(event.getLastAttemptId());
        return new PlanChainVO.EventChainEdgeVO(ProcessText.id(event.getId()),
            ProcessText.id(event.getEventVersion()), event.getEventCode(),
            definition == null ? null : definition.getDisplayName(),
            event.getEventDirection() == null ? null : event.getEventDirection().name(),
            event.getEventRole() == null ? null : event.getEventRole().name(),
            definition == null ? null : definition.getEventTypeKey(),
            definition == null ? null : definition.getEventTypeVersion(),
            event.getCallContractKey(), event.getCallContractVersion(), event.getLocalActionKey(),
            event.getLocalActionVersion(), event.getSystemCode(),
            mode(event),
            event.getSourceStateCode(), event.getTargetStateCode(), eventStatus(event),
            attempt == null ? null : attempt.getAttemptNo(), blockedReason(event, attempt),
            event.getWaitReason() == null ? null : event.getWaitReason().name(),
            definition == null || graph == null ? List.of() : targetValidation(graph, event, attempt),
            time(event.getUpdateTime()), eventRanks.get(event.getId()),
            eventRanks.containsKey(event.getId()));
    }

    private List<PlanChainVO.BranchChainVO> branches(RuntimeSnapshot snapshot) {
        List<PlanChainVO.BranchChainVO> result = new ArrayList<>();
        for (ProcessToken token : snapshot.tokens()) {
            List<ProcessEvent> tokenEvents = snapshot.events().stream()
                .filter(item -> Objects.equals(item.getTokenId(), token.getId())).toList();
            ProcessEvent current = currentEvent(tokenEvents);
            ProcessState state = snapshot.graph().state(token.getStateCode());
            List<PlanChainVO.StateNodeVO> next = nextStates(token, snapshot);
            result.add(new PlanChainVO.BranchChainVO(ProcessText.id(token.getId()), token.getBranchKey(),
                token.getBranchKey(), token.getTokenStatus() == null ? null : token.getTokenStatus().name(),
                ProcessText.id(token.getTokenVersion()), token.getStateCode(),
                state == null ? null : new PlanChainVO.StateNodeVO(state.getStateCode(),
                    state.getDisplayName(), state.getConditionDescription(),
                    state.getStateKind() == null ? null : state.getStateKind().name(), state.getTerminal(),
                    Boolean.TRUE, time(token.getUpdateTime()), Boolean.TRUE,
                    List.of(validation(state, snapshot.events(), attemptsFor(snapshot, state),
                        modeFor(snapshot, state))), null, null, null),
                next, tokenEvents.stream().map(item -> eventEdge(snapshot.graph(), item,
                    snapshot.eventRanks())).toList(),
                current == null ? null : eventEdge(snapshot.graph(), current, snapshot.eventRanks()),
                time(token.getUpdateTime()), token.getScopeKey()));
        }
        return result;
    }

    private List<PlanChainVO.StateNodeVO> nextStates(ProcessToken token, RuntimeSnapshot snapshot) {
        if (token.getStateCode() == null || snapshot.graph() == null) {
            return List.of();
        }
        ProcessState current = snapshot.graph().state(token.getStateCode());
        if (current != null && Boolean.TRUE.equals(current.getTerminal())) {
            return List.of();
        }
        List<ProcessEventDefinition> outgoing = snapshot.graph().eventCodes().stream()
            .map(snapshot.graph()::event)
            .filter(Objects::nonNull)
            .filter(item -> Objects.equals(item.getSourceStateCode(), token.getStateCode()))
            .toList();
        List<ProcessEvent> tokenEvents = snapshot.events().stream()
            .filter(item -> Objects.equals(item.getTokenId(), token.getId())).toList();
        ProcessEvent processing = tokenEvents.stream()
            .filter(item -> item.getEventStatus() == EventStatus.PROCESSING).findFirst().orElse(null);
        Set<String> selectedCodes = new HashSet<>();
        Set<String> outgoingCodes = outgoing.stream().map(ProcessEventDefinition::getEventCode)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (processing != null) {
            selectedCodes.add(processing.getEventCode());
        } else {
            for (ProcessEvent item : tokenEvents) {
                if (item.getEventStatus() != EventStatus.NOT_SELECTED
                    && outgoingCodes.contains(item.getEventCode())) {
                    selectedCodes.add(item.getEventCode());
                }
            }
        }
        if (!selectedCodes.isEmpty()) {
            outgoing = outgoing.stream().filter(item -> selectedCodes.contains(item.getEventCode())).toList();
        }
        Map<String, PlanChainVO.StateNodeVO> result = new LinkedHashMap<>();
        for (ProcessEventDefinition definition : outgoing) {
            ProcessState target = snapshot.graph().state(definition.getTargetStateCode());
            if (target == null) {
                continue;
            }
            boolean decided = selectedCodes.contains(definition.getEventCode());
            result.putIfAbsent(target.getStateCode() + ":" + decided,
                new PlanChainVO.StateNodeVO(target.getStateCode(), target.getDisplayName(),
                    target.getConditionDescription(), target.getStateKind() == null ? null
                        : target.getStateKind().name(), target.getTerminal(), Boolean.FALSE, null,
                    Boolean.FALSE, List.of(validation(target, snapshot.events(),
                        attemptsFor(snapshot, target), modeFor(snapshot, target))), decided, !decided,
                    null));
        }
        return new ArrayList<>(result.values());
    }

    private PlanChainVO.ExceptionDescriptionVO exception(RuntimeSnapshot snapshot) {
        ProcessEvent failed = snapshot.events().stream()
            .filter(item -> item.getEffectResult() == EffectResult.UNKNOWN
                || item.getEventStatus() == EventStatus.FAILED_FINAL
                || (item.getEventStatus() == EventStatus.MANUAL_PENDING
                && item.getWaitReason() == WaitReason.EXECUTION_FAILED))
            .max(eventComparator()).orElse(null);
        if (failed == null && !EXCEPTION_PLAN.contains(snapshot.instance().getPlanStatus())) {
            return null;
        }
        ProcessAttempt attempt = failed == null || failed.getLastAttemptId() == null ? null
            : attemptMapper.selectById(failed.getLastAttemptId());
        ProcessEventDefinition definition = failed == null ? null
            : snapshot.graph().event(failed.getEventCode());
        ProcessState state = failed == null ? null
            : snapshot.graph().state(failed.getTargetStateCode());
        boolean unknown = failed != null && (failed.getEffectResult() == EffectResult.UNKNOWN
            || (Boolean.TRUE.equals(failed.getSent()) && (attempt == null
            || attempt.getCheckpointStatus() == CheckpointStatus.SEND_INTENT
            || attempt.getCheckpointStatus() == CheckpointStatus.UNKNOWN)));
        return new PlanChainVO.ExceptionDescriptionVO(
            state == null ? snapshot.instance().getPlanStatus().name() : state.getStateCode(),
            state == null ? PLAN_STATUS_TEXT.get(snapshot.instance().getPlanStatus()) : state.getDisplayName(),
            failed == null ? null : ProcessText.id(failed.getId()),
            failed == null ? null : failed.getEventCode(),
            definition == null ? null : definition.getDisplayName(),
            failed == null ? null : failed.getCallContractKey(),
            failed == null ? null : failed.getErrorCode(),
            failed == null || failed.getEffectResult() == null ? null : failed.getEffectResult().name(),
            failed != null && failed.getEventStatus() == EventStatus.MANUAL_PENDING && !unknown,
            failed == null ? null : failed.getErrorMessage(), failed == null ? null : failed.getSourceStateCode(),
            failed == null ? null : failed.getTargetStateCode(),
            attempt == null || attempt.getCheckpointStatus() == null ? null
                : attempt.getCheckpointStatus().name(), unknown);
    }

    private List<PlanChainVO.SystemContextVO> systemContexts(RuntimeSnapshot snapshot) {
        Map<String, Long> counts = snapshot.events().stream()
            .filter(item -> item.getSystemCode() != null)
            .collect(Collectors.groupingBy(ProcessEvent::getSystemCode, LinkedHashMap::new, Collectors.counting()));
        return snapshot.contexts().stream().map(item -> {
            boolean manual = item.getPostingMode() == PostingMode.MANUAL_CONFIRM;
            boolean confirmable = manual && !Boolean.TRUE.equals(item.getFirstConfirmed())
                && (snapshot.instance().getPlanStatus() == PlanStatus.RUNNING
                || snapshot.instance().getPlanStatus() == PlanStatus.MANUAL_PENDING);
            return new PlanChainVO.SystemContextVO(item.getSystemCode(), item.getSystemCode(),
                item.getPostingMode() == null ? null : item.getPostingMode().name(),
                item.getFirstConfirmed(), time(item.getConfirmedAt()), ProcessText.id(item.getConfirmedBy()),
                confirmable, confirmable || !manual ? List.of() : List.of("非待确认系统"),
                counts.getOrDefault(item.getSystemCode(), 0L).intValue());
        }).toList();
    }

    private PlanChainVO.ValidationVO validation(ProcessState state, List<ProcessEvent> events,
                                                List<ProcessAttempt> attempts, String mode) {
        ProcessAttempt evidence = attempts.stream()
            .filter(item -> Objects.equals(item.getTargetStateCode(), state.getStateCode()))
            .max(Comparator.comparing(ProcessAttempt::getAttemptNo,
                Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
        String result = "NOT_EVALUATED";
        if (evidence != null && evidence.getCheckpointStatus() != null) {
            result = switch (evidence.getCheckpointStatus()) {
                case SUCCEEDED, ACCEPTED, NO_EFFECT -> "PASS";
                case SKIPPED_MODE -> "NOT_APPLICABLE";
                case FAILED -> "FAIL";
                default -> "NOT_EVALUATED";
            };
        }
        return new PlanChainVO.ValidationVO(
            state.getValidatorKey() == null || state.getValidatorKey().isBlank()
                ? "STATE:" + state.getStateCode() : state.getValidatorKey(),
            state.getConditionDescription() == null || state.getConditionDescription().isBlank()
                ? state.getDisplayName() : state.getConditionDescription(),
            "STATE", List.of("冻结状态定义", "事件检查点"),
            evidence == null ? null : "attempt:" + evidence.getId(), result,
            evidence == null ? null : time(evidence.getRecordedAt() == null ? evidence.getEndedAt()
                : evidence.getRecordedAt()),
            state.getValidatorVersion(), mode);
    }

    private List<PlanChainVO.ValidationVO> targetValidation(DefinitionGraph graph, ProcessEvent event,
                                                             ProcessAttempt attempt) {
        ProcessState target = graph.state(event.getTargetStateCode());
        if (target == null) {
            return List.of();
        }
        return List.of(validation(target, List.of(event),
            attempt == null ? List.of() : List.of(attempt), null));
    }

    private List<ProcessAttempt> attemptsFor(RuntimeSnapshot snapshot, ProcessState state) {
        List<ProcessAttempt> result = new ArrayList<>();
        for (ProcessEvent event : snapshot.events()) {
            List<ProcessAttempt> values = snapshot.attempts().getOrDefault(event.getId(), List.of());
            for (ProcessAttempt attempt : values) {
                if (Objects.equals(attempt.getTargetStateCode(), state.getStateCode())) {
                    result.add(attempt);
                }
            }
        }
        return result;
    }

    private String modeFor(RuntimeSnapshot snapshot, ProcessState state) {
        ProcessEvent event = snapshot.events().stream()
            .filter(item -> Objects.equals(item.getTargetStateCode(), state.getStateCode()))
            .findFirst().orElse(null);
        if (event == null) {
            return null;
        }
        return snapshot.contexts().stream()
            .filter(item -> Objects.equals(item.getSystemCode(), event.getSystemCode()))
            .map(item -> item.getPostingMode() == null ? null : item.getPostingMode().name())
            .findFirst().orElse(null);
    }

    private ProcessEvent currentEvent(List<ProcessToken> tokens, List<ProcessEvent> events) {
        for (ProcessToken token : tokens) {
            ProcessEvent current = currentEvent(events.stream()
                .filter(item -> Objects.equals(item.getTokenId(), token.getId())).toList());
            if (current != null) {
                return current;
            }
        }
        return null;
    }

    private ProcessEvent currentEvent(List<ProcessEvent> events) {
        ProcessEvent processing = events.stream()
            .filter(item -> item.getEventStatus() == EventStatus.PROCESSING).findFirst().orElse(null);
        if (processing != null) {
            return processing;
        }
        ProcessEvent pending = events.stream()
            .filter(item -> item.getEventStatus() == EventStatus.PENDING
                || item.getEventStatus() == EventStatus.RETRY_WAIT)
            .min(eventComparator()).orElse(null);
        if (pending != null) {
            return pending;
        }
        return events.stream().filter(item -> !TERMINAL_EVENT.contains(item.getEventStatus()))
            .max(eventComparator()).orElse(null);
    }

    private Comparator<ProcessEvent> eventComparator() {
        return Comparator.comparing(ProcessEvent::getUpdateTime,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ProcessEvent::getId, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    private String actionReason(ProcessEvent event, boolean unknown) {
        if (event.getEventStatus() == EventStatus.PROCESSING) {
            return "正在执行";
        }
        if (TERMINAL_EVENT.contains(event.getEventStatus())) {
            return "事件已结束";
        }
        if (unknown) {
            return "结果未知，需先对账";
        }
        return "后端未允许";
    }

    private String blockedReason(ProcessEvent event, ProcessAttempt attempt) {
        if (event == null) {
            return null;
        }
        if (event.getErrorMessage() != null && !event.getErrorMessage().isBlank()) {
            return event.getErrorMessage();
        }
        if (event.getWaitReason() != null) {
            return event.getWaitReason().name();
        }
        return attempt == null || attempt.getErrorMessage() == null ? null : attempt.getErrorMessage();
    }

    private String blockedReason(ProcessInstance instance, ProcessEvent current) {
        if (current != null && (current.getEventStatus() == EventStatus.MANUAL_PENDING
            || current.getEffectResult() == EffectResult.UNKNOWN)) {
            return blockedReason(current, current.getLastAttemptId() == null ? null
                : attemptMapper.selectById(current.getLastAttemptId()));
        }
        return instance.getExceptionSummary();
    }

    private String exceptionState(ProcessInstance instance, List<ProcessToken> tokens,
                                  List<ProcessEvent> events) {
        if (instance.getPlanStatus() != PlanStatus.EXCEPTION_HOLD
            && instance.getPlanStatus() != PlanStatus.MANUAL_PENDING) {
            return null;
        }
        ProcessEvent failed = events.stream().filter(item -> item.getEffectResult() == EffectResult.UNKNOWN
            || item.getEventStatus() == EventStatus.FAILED_FINAL).max(eventComparator()).orElse(null);
        if (failed != null) {
            return failed.getTargetStateCode();
        }
        return tokens.stream().map(ProcessToken::getStateCode).filter(Objects::nonNull).findFirst()
            .orElse(instance.getPlanStatus().name());
    }

    private String eventStatus(ProcessEvent event) {
        if (event.getEffectResult() == EffectResult.UNKNOWN) {
            return "UNKNOWN";
        }
        if (event.getEffectResult() == EffectResult.SKIPPED_MODE) {
            return "SKIPPED_MODE";
        }
        if (event.getEventStatus() == EventStatus.WAIT_EXTERNAL_TRIGGER
            && event.getWaitReason() == WaitReason.RESULT_CALLBACK) {
            return "WAIT_RESULT";
        }
        return event.getEventStatus() == null ? null : event.getEventStatus().name();
    }

    private boolean unknown(ProcessEvent event, ProcessAttempt attempt) {
        if (event.getEffectResult() == EffectResult.UNKNOWN) {
            return true;
        }
        return Boolean.TRUE.equals(event.getSent()) && (attempt == null
            || attempt.getCheckpointStatus() == CheckpointStatus.SEND_INTENT
            || attempt.getCheckpointStatus() == CheckpointStatus.UNKNOWN);
    }

    private String mode(ProcessEvent event) {
        ProcessSystemContext context = contextMapper.selectOne(Wrappers.<ProcessSystemContext>lambdaQuery()
            .eq(ProcessSystemContext::getInstanceId, event.getInstanceId())
            .eq(ProcessSystemContext::getSystemCode, event.getSystemCode()).last("LIMIT 1"));
        return context == null || context.getPostingMode() == null ? null : context.getPostingMode().name();
    }

    private Integer attemptNo(ProcessEvent event) {
        if (event.getLastAttemptId() == null) {
            return null;
        }
        ProcessAttempt attempt = attemptMapper.selectById(event.getLastAttemptId());
        return attempt == null ? null : attempt.getAttemptNo();
    }

    private List<Long> relatedEventIds(ProcessEvent event) {
        List<ProcessEvent> values = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, event.getInstanceId()));
        Set<Long> result = new LinkedHashSet<>();
        for (ProcessEvent item : values) {
            if (item.getId().equals(event.getId())) {
                continue;
            }
            if ((event.getOriginOutboundEventId() != null
                && event.getOriginOutboundEventId().equals(item.getId()))
                || (item.getOriginOutboundEventId() != null
                && item.getOriginOutboundEventId().equals(event.getId()))) {
                result.add(item.getId());
            }
        }
        return result.stream().sorted().toList();
    }

    private ExecutionOverviewVO.PriorityVO priority(RuntimeSnapshot snapshot) {
        PlanTypeInfo type = planTypes(snapshot.instance().getScheduleId()).getOrDefault(
            snapshot.instance().getPlanType(), new PlanTypeInfo(0, snapshot.instance().getPlanType()));
        return new ExecutionOverviewVO.PriorityVO(type.priority(),
            intValue(snapshot.instance().getInstancePriority()), maxEventPriority(snapshot.events()));
    }

    private int maxEventPriority(List<ProcessEvent> events) {
        return events.stream().filter(item -> item.getEventStatus() == null
                || EXECUTABLE_EVENT.contains(item.getEventStatus()))
            .map(ProcessEvent::getPriority).filter(Objects::nonNull).max(Integer::compareTo).orElse(0);
    }

    private String eventName(DefinitionGraph graph, String eventCode) {
        ProcessEventDefinition definition = graph == null ? null : graph.event(eventCode);
        return definition == null ? eventCode : definition.getDisplayName();
    }

    private Map<Long, List<ProcessToken>> tokens(List<ProcessInstance> instances) {
        if (instances.isEmpty()) {
            return Map.of();
        }
        return tokenMapper.selectList(Wrappers.<ProcessToken>lambdaQuery()
                .in(ProcessToken::getInstanceId, instances.stream().map(ProcessInstance::getId).toList())
                .orderByAsc(ProcessToken::getId))
            .stream().collect(Collectors.groupingBy(ProcessToken::getInstanceId, LinkedHashMap::new,
                Collectors.toList()));
    }

    private Map<Long, List<ProcessEvent>> events(Collection<ProcessInstance> instances) {
        if (instances.isEmpty()) {
            return Map.of();
        }
        return eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
                .in(ProcessEvent::getInstanceId, instances.stream().map(ProcessInstance::getId).toList())
                .orderByAsc(ProcessEvent::getId))
            .stream().collect(Collectors.groupingBy(ProcessEvent::getInstanceId, LinkedHashMap::new,
                Collectors.toList()));
    }

    private Map<Long, List<ProcessAttempt>> attempts(List<ProcessEvent> events) {
        if (events.isEmpty()) {
            return Map.of();
        }
        return attemptMapper.selectList(Wrappers.<ProcessAttempt>lambdaQuery()
                .in(ProcessAttempt::getEventId, events.stream().map(ProcessEvent::getId).toList())
                .orderByDesc(ProcessAttempt::getAttemptNo))
            .stream().collect(Collectors.groupingBy(ProcessAttempt::getEventId, LinkedHashMap::new,
                Collectors.toList()));
    }

    private Map<Long, PlanDefinition> definitions(Collection<ProcessInstance> instances) {
        if (instances.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = instances.stream().map(ProcessInstance::getDefinitionVersionId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, PlanDefinition> result = new HashMap<>();
        for (Long id : ids) {
            PlanDefinition definition = definitionMapper.selectById(id);
            if (definition != null) {
                result.put(id, definition);
            }
        }
        return result;
    }

    private Map<Long, DefinitionGraph> graphs(Collection<Long> versionIds) {
        Map<Long, DefinitionGraph> result = new HashMap<>();
        for (Long id : versionIds) {
            result.put(id, runtimeDao.graph(id));
        }
        return result;
    }

    private Map<String, PlanTypeInfo> planTypes(long scheduleId) {
        Map<String, PlanTypeInfo> result = new LinkedHashMap<>();
        for (BusinessSchedulePlanType type : planTypeMapper.selectList(
            Wrappers.<BusinessSchedulePlanType>lambdaQuery()
                .eq(BusinessSchedulePlanType::getScheduleId, scheduleId))) {
            result.put(type.getPlanType(), new PlanTypeInfo(intValue(type.getPriority()),
                type.getPlanTypeName() == null ? type.getPlanType() : type.getPlanTypeName()));
        }
        return result;
    }

    private Map<Long, PendingRank> pendingStatuses(BusinessSchedule schedule,
                                                   List<ProcessInstanceQueryRow> rows) {
        List<Long> supervisorIds = rows.stream().map(ProcessInstance::getSupervisorInstanceId)
            .filter(Objects::nonNull).distinct().toList();
        Map<Long, ProcessInstance> supervisors = supervisorIds.isEmpty() ? Map.of()
            : instanceMapper.selectList(Wrappers.<ProcessInstance>lambdaQuery()
                .in(ProcessInstance::getId, supervisorIds))
            .stream().collect(Collectors.toMap(ProcessInstance::getId, Function.identity()));
        Map<Long, ProcessScheduleControl> controls = supervisorIds.isEmpty() ? Map.of()
            : controlMapper.selectList(Wrappers.<ProcessScheduleControl>lambdaQuery()
                .in(ProcessScheduleControl::getSupervisorInstanceId, supervisorIds)
                .eq(ProcessScheduleControl::getDuty, "DISPATCH"))
            .stream().collect(Collectors.toMap(ProcessScheduleControl::getSupervisorInstanceId,
                Function.identity(), (left, right) -> left));
        Map<Long, Long> held = new HashMap<>();
        if (!supervisorIds.isEmpty()) {
            for (ProcessScheduleControl item : controlMapper.selectList(
                Wrappers.<ProcessScheduleControl>lambdaQuery()
                    .in(ProcessScheduleControl::getSupervisorInstanceId, supervisorIds)
                    .eq(ProcessScheduleControl::getScopeType,
                        com.innolux.iws.process.enums.ScheduleControlScope.PLAN_SLOT)
                    .eq(ProcessScheduleControl::getSlotState,
                        com.innolux.iws.process.enums.SlotState.HELD))) {
                held.merge(item.getSupervisorInstanceId(), 1L, Long::sum);
            }
        }
        Map<Long, PendingRank> result = new HashMap<>();
        for (ProcessInstanceQueryRow instance : rows) {
            Long supervisorId = instance.getSupervisorInstanceId();
            ProcessInstance supervisor = supervisorId == null ? null : supervisors.get(supervisorId);
            PlanDefinition supervisorDefinition = supervisor == null ? null
                : definitionMapper.selectById(supervisor.getDefinitionVersionId());
            ProcessScheduleControl control = supervisorId == null ? null : controls.get(supervisorId);
            boolean paused = control != null && control.getControlState() == ControlState.MANUAL_PAUSED;
            boolean full = supervisorDefinition != null && supervisorDefinition.getActivePlanLimit() != null
                && held.getOrDefault(supervisorId, 0L) >= supervisorDefinition.getActivePlanLimit();
            boolean hold = Boolean.TRUE.equals(instance.getHoldStart());
            String queueStatus = hold ? "HELD_BEFORE_START" : paused ? "SUPERVISOR_PAUSED"
                : full ? "WAITING_PLAN_SLOT" : "WAITING_QUEUE";
            String blockReason = hold ? "HELD_BEFORE_START" : paused ? "SUPERVISOR_PAUSED"
                : full ? "WAIT_PLAN_SLOT" : null;
            result.put(instance.getId(), new PendingRank(hold || paused ? null : instance.getQueueRank(),
                instance.getScheduleScope(), !hold && !paused, blockReason, queueStatus));
        }
        return result;
    }

    private PendingRank pendingRank(ProcessInstance instance) {
        if (instance.getPlanStatus() != PlanStatus.CREATED) {
            return null;
        }
        BusinessSchedule schedule = instance.getScheduleId() == null ? null
            : scheduleMapper.selectById(instance.getScheduleId());
        if (schedule == null) {
            return new PendingRank(null, instance.getScheduleScope(), !Boolean.TRUE.equals(instance.getHoldStart()),
                Boolean.TRUE.equals(instance.getHoldStart()) ? "HELD_BEFORE_START" : null,
                Boolean.TRUE.equals(instance.getHoldStart()) ? "HELD_BEFORE_START" : "WAITING_QUEUE");
        }
        if (Boolean.TRUE.equals(instance.getHoldStart())) {
            return new PendingRank(null, instance.getScheduleScope(), Boolean.FALSE, "HELD_BEFORE_START",
                "HELD_BEFORE_START");
        }
        if (instance.getSupervisorInstanceId() == null) {
            return new PendingRank(null, instance.getScheduleScope(), Boolean.FALSE, "SUPERVISOR_NOT_BOUND",
                "WAITING_QUEUE");
        }
        ProcessInstance supervisor = instanceMapper.selectById(instance.getSupervisorInstanceId());
        SupervisorGate gate = supervisorGate(supervisor);
        Integer rank = gate.paused() ? null
            : queryMapper.selectPendingRank(schedule.getBusinessCode(), instance.getId());
        String queueStatus = gate.paused() ? "SUPERVISOR_PAUSED"
            : gate.full() ? "WAITING_PLAN_SLOT" : "WAITING_QUEUE";
        String blockReason = gate.paused() ? "SUPERVISOR_PAUSED"
            : gate.full() ? "WAIT_PLAN_SLOT" : null;
        return new PendingRank(rank, instance.getScheduleScope(), !gate.paused(), blockReason, queueStatus);
    }

    private SupervisorGate supervisorGate(ProcessInstance supervisor) {
        if (supervisor == null) {
            return new SupervisorGate(false, false);
        }
        ProcessScheduleControl control = controlMapper.selectOne(
            Wrappers.<ProcessScheduleControl>lambdaQuery()
                .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
                .eq(ProcessScheduleControl::getDuty, "DISPATCH")
                .last("LIMIT 1"));
        boolean paused = control != null && control.getControlState() == ControlState.MANUAL_PAUSED;
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        boolean full = false;
        if (definition != null && definition.getActivePlanLimit() != null) {
            long held = controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
                .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
                .eq(ProcessScheduleControl::getScopeType,
                    com.innolux.iws.process.enums.ScheduleControlScope.PLAN_SLOT)
                .eq(ProcessScheduleControl::getSlotState,
                    com.innolux.iws.process.enums.SlotState.HELD));
            full = held >= definition.getActivePlanLimit();
        }
        return new SupervisorGate(paused, full);
    }

    private <T> Page<T> page(ProcessExecutionOverviewQuery.PageSpec spec) {
        Page<T> page = new Page<>(spec.pageNum(), spec.pageSize());
        page.setOptimizeCountSql(false);
        return page;
    }

    private Map<Long, Integer> eventRanks(long scheduleId, Collection<ProcessEvent> events) {
        if (scheduleId <= 0 || events.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = events.stream()
            .filter(item -> item.getEventStatus() == EventStatus.PENDING
                || item.getEventStatus() == EventStatus.RETRY_WAIT)
            .map(ProcessEvent::getId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        Set<Long> wanted = Set.copyOf(ids);
        List<PlanCandidateQueryService.CandidateRow> candidates = candidateQueryService
            .loadQueueCandidates(scheduleId, ALL_CANDIDATES);
        for (int index = 0; index < candidates.size(); index++) {
            long eventId = candidates.get(index).eventId();
            if (wanted.contains(eventId)) {
                result.put(eventId, index + 1);
            }
        }
        return result;
    }

    private ProcessRequestQuery normalizeRequestQuery(ProcessRequestQuery query) {
        if (query == null) {
            return new ProcessRequestQuery(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        }
        return new ProcessRequestQuery(trim(query.requestId()), trim(query.direction()),
            trim(query.purpose()), trim(query.contractKey()), trim(query.resultCode()),
            trim(query.effectResult()), trim(query.instanceId()), trim(query.eventId()),
            trim(query.correlationId()), trim(query.businessScheduleCode()), trim(query.businessId()),
            trim(query.taskKey()), query.linked(), normalizeTime(query.startedFrom()),
            normalizeTime(query.startedTo()), query.pageNum(), query.pageSize());
    }

    private void authorizeRequestScope(ProcessRequestQuery query) {
        if (!blank(query.instanceId())) {
            objectAuthorizer.authorizeInstance(parseId(query.instanceId(), "instanceId"), "QUERY");
        }
        if (!blank(query.eventId())) {
            objectAuthorizer.authorizeEvent(parseId(query.eventId(), "eventId"), "QUERY");
        }
        if (!blank(query.businessScheduleCode())) {
            BusinessSchedule schedule = scheduleMapper.selectOne(Wrappers.<BusinessSchedule>lambdaQuery()
                .eq(BusinessSchedule::getBusinessCode, query.businessScheduleCode()).last("LIMIT 1"));
            if (schedule == null) {
                throw new ServiceException("业务调度不存在: " + query.businessScheduleCode());
            }
            objectAuthorizer.authorizeSchedule(schedule.getId(), "QUERY");
        }
    }

    private ProcessRequestRecordVO requestRecordView(ProcessRequestRecordRow row) {
        String direction = row.getDirection();
        String purpose = row.getPurpose();
        String effectResult = row.getEffectResult();
        String association = associationStatus(row);
        return new ProcessRequestRecordVO(row.getRequestId(), direction,
            display(REQUEST_DIRECTION_TEXT, direction), purpose,
            display(REQUEST_PURPOSE_TEXT, purpose), row.getSourceRequestId(),
            row.getCorrelationId(), row.getParentRequestId(), row.getPeerRequestId(),
            row.getCallerSystemCode(), row.getReceiverSystemCode(), row.getModuleCode(),
            row.getClientIp(), row.getAccessSummary(),
            row.getContractKey(), row.getContractVersion(), row.getEffectKey(),
            ProcessText.id(row.getInstanceId()), ProcessText.id(row.getEventId()),
            ProcessText.id(row.getAttemptId()), ProcessText.id(row.getReceiptId()),
            row.getBusinessScheduleCode(), row.getPlanType(), row.getBusinessId(), row.getTaskKey(),
            row.getHttpStatus(), row.getResultCode(), effectResult,
            display(EFFECT_RESULT_TEXT, effectResult), row.getSent(),
            time(row.getStartedAt()), time(row.getEndedAt()), time(row.getEvidenceAt()),
            association, ASSOCIATION_TEXT.get(association));
    }

    private String associationStatus(ProcessRequestRecordRow row) {
        if (row.getInstanceId() != null || row.getEventId() != null) {
            return "LINKED";
        }
        if (row.getAttemptId() == null && row.getReceiptId() == null) {
            return "UNLINKED";
        }
        return "UNKNOWN";
    }

    private String display(Map<String, String> values, String code) {
        return code == null ? null : values.getOrDefault(code, code);
    }

    private ProcessInstance requireVisibleInstance(long instanceId) {
        ProcessInstance instance = queryMapper.selectVisibleInstance(instanceId);
        if (instance == null) {
            throw new ServiceException("计划实例不存在或无权访问: " + instanceId);
        }
        return instance;
    }

    private ProcessEvent requireVisibleEvent(long eventId) {
        ProcessEvent event = queryMapper.selectVisibleEvent(eventId);
        if (event == null) {
            throw new ServiceException("计划事件不存在或无权访问: " + eventId);
        }
        return event;
    }

    private PlanDefinition requireDefinition(long versionId) {
        PlanDefinition definition = definitionMapper.selectById(versionId);
        if (definition == null) {
            throw new ServiceException("计划定义不存在: " + versionId);
        }
        return definition;
    }

    private ProcessAuditEntryVO auditEntry(ProcessActionAudit audit) {
        String action = audit.getActionCode();
        String result = audit.getResult() == null ? null : audit.getResult().name();
        return new ProcessAuditEntryVO(ProcessText.id(audit.getId()), time(audit.getOccurredAt()), action,
            ACTION_TEXT.getOrDefault(action, action),
            audit.getActorName() == null ? ProcessText.id(audit.getActorUserId()) : audit.getActorName(),
            audit.getActionSource() == null ? null : audit.getActionSource().name(), result,
            audit.getResult() == null ? null : AUDIT_RESULT_TEXT.get(audit.getResult()),
            audit.getReason(), audit.getBeforeStatus(), audit.getAfterStatus(),
            ProcessText.id(audit.getBeforeVersion()), ProcessText.id(audit.getAfterVersion()),
            audit.getCommandKey(), ProcessText.id(audit.getInstanceId()), ProcessText.id(audit.getEventId()),
            ProcessText.id(audit.getAttemptId()), audit.getRequestId(), audit.getRoundNo(),
            ProcessText.id(audit.getChildInstanceId()), audit.getDecisionSummary());
    }

    private ProcessAuditQuery normalize(ProcessAuditQuery query) {
        return new ProcessAuditQuery(trim(query.businessScheduleCode()), trim(query.instanceId()),
            trim(query.eventId()), trim(query.operator()), trim(query.action()), trim(query.result()),
            normalizeTime(query.occurredFrom()), normalizeTime(query.occurredTo()),
            query.pageNum(), query.pageSize());
    }

    private String normalizeTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replace('T', ' ').replace("Z", "");
    }

    private long parseId(String value, String name) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new ServiceException(name + " 不是合法字符串 ID: " + value);
        }
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private String time(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record PlanTypeInfo(int priority, String name) {
    }

    private record PendingRank(Integer queueRank, String resourceScope, boolean eligible,
                               String blockReason, String queueStatus) {
    }

    private record SupervisorGate(boolean paused, boolean full) {
    }

    private record RuntimeSnapshot(ProcessInstance instance, PlanDefinition definition,
                                   DefinitionGraph graph, List<ProcessState> states,
                                   List<ProcessToken> tokens, List<ProcessEvent> events,
                                   Map<Long, List<ProcessAttempt>> attempts,
                                   List<ProcessSystemContext> contexts,
                                   Map<Long, Integer> eventRanks, LocalDateTime asOf) {
    }
}
