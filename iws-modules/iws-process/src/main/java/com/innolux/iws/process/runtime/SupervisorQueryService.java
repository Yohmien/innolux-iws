/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督列表、详情、轮次与调度记录查询；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.SupervisorDetailVO;
import com.innolux.iws.process.api.SupervisorDispatchRecordVO;
import com.innolux.iws.process.api.SupervisorRoundVO;
import com.innolux.iws.process.api.SupervisorSummaryVO;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读监督查询；查询不触发调度。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessActionAuditMapper auditMapper;

    private final JdbcTemplate jdbcTemplate;

    private final ProcessObjectAuthorizer objectAuthorizer;

    private final ObjectProvider<ProcessAccessPolicy> policyProvider;

    /**
     * 构造服务。
     *
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param definitionMapper 定义 Mapper
     * @param eventMapper 事件 Mapper
     * @param auditMapper 审计 Mapper
     * @param jdbcTemplate JDBC 模板
     * @param objectAuthorizer 对象范围授权
     * @param policyProvider 业务范围策略
     */
    public SupervisorQueryService(ProcessInstanceMapper instanceMapper,
                                  ProcessScheduleControlMapper controlMapper,
                                  PlanDefinitionMapper definitionMapper,
                                  ProcessEventMapper eventMapper,
                                  ProcessActionAuditMapper auditMapper,
                                  JdbcTemplate jdbcTemplate,
                                  ProcessObjectAuthorizer objectAuthorizer,
                                  ObjectProvider<ProcessAccessPolicy> policyProvider) {
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.definitionMapper = definitionMapper;
        this.eventMapper = eventMapper;
        this.auditMapper = auditMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectAuthorizer = objectAuthorizer;
        this.policyProvider = policyProvider;
    }

    /**
     * 分页查询监督。
     *
     * @param scheduleId 业务调度 ID
     * @param planType 计划类型
     * @param scope 范围
     * @param mode 模式
     * @param status 状态
     * @param pageQuery 分页
     * @return 分页
     */
    public PageResult<SupervisorSummaryVO> pageSupervisors(long scheduleId, String planType, String scope,
                                                           String mode, String status, PageQuery pageQuery) {
        objectAuthorizer.authorizeSchedule(scheduleId, "QUERY");
        PlanMode modeValue = mode == null || mode.isBlank() ? null : parseMode(mode);
        PlanStatus statusValue = status == null || status.isBlank() ? null : parseStatus(status);
        var wrapper = Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getPlanRole, PlanRole.SUPERVISOR)
            .eq(ProcessInstance::getScheduleId, scheduleId)
            .eq(planType != null && !planType.isBlank(), ProcessInstance::getPlanType, planType)
            .eq(scope != null && !scope.isBlank(), ProcessInstance::getScheduleScope, scope)
            .eq(modeValue != null, ProcessInstance::getModeSnapshot, modeValue)
            .eq(statusValue != null, ProcessInstance::getPlanStatus, statusValue)
            .orderByAsc(ProcessInstance::getPlanType)
            .orderByAsc(ProcessInstance::getScheduleScope);
        boolean superObject = StpUtil.hasPermission("process:object:all");
        ProcessAccessPolicy policy = policyProvider.getIfAvailable();
        List<SupervisorSummaryVO> visible = new ArrayList<>();
        for (ProcessInstance supervisor : instanceMapper.selectList(wrapper)) {
            if (superObject || (policy != null && policy.canAccess(childTarget(supervisor)))) {
                visible.add(summary(supervisor));
            }
        }
        int pageNum = pageNum(pageQuery);
        int pageSize = pageSize(pageQuery);
        int from = Math.min(visible.size(), (pageNum - 1) * pageSize);
        int to = Math.min(visible.size(), from + pageSize);
        return PageResult.build(visible.subList(from, to), visible.size());
    }

    /**
     * 监督详情。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @return 详情
     */
    public SupervisorDetailVO detail(long supervisorInstanceId) {
        objectAuthorizer.authorizeInstance(supervisorInstanceId, "QUERY");
        ProcessInstance supervisor = requireSupervisor(supervisorInstanceId);
        SupervisorSummaryVO summary = summary(supervisor);
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        long eventCount = eventMapper.selectCount(Wrappers.<com.innolux.iws.process.domain.ProcessEvent>lambdaQuery()
            .eq(com.innolux.iws.process.domain.ProcessEvent::getInstanceId, supervisorInstanceId));
        List<String> errors = auditMapper.selectList(Wrappers.<ProcessActionAudit>lambdaQuery()
                .eq(ProcessActionAudit::getSupervisorInstanceId, supervisorInstanceId)
                .eq(ProcessActionAudit::getResult, com.innolux.iws.process.enums.AuditResult.FAILED)
                .orderByDesc(ProcessActionAudit::getOccurredAt)
                .orderByDesc(ProcessActionAudit::getId)
                .last("LIMIT 5"))
            .stream().map(ProcessActionAudit::getReason).filter(item -> item != null).toList();
        return new SupervisorDetailVO(summary, definition == null ? null : definition.getPlanCode(),
            definition == null ? null : definition.getPlanName(),
            definition == null || definition.getStatus() == null ? null : definition.getStatus().name(),
            definition == null ? null : definition.getRevision(), eventCount, summary.roundNo(),
            latestActivity(supervisorInstanceId), errors);
    }

    /**
     * 分页查询轮次。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param roundNo 轮次号
     * @param status 状态
     * @param pageQuery 分页
     * @return 分页
     */
    public PageResult<SupervisorRoundVO> pageRounds(long supervisorInstanceId, Long roundNo, String status,
                                                    PageQuery pageQuery) {
        objectAuthorizer.authorizeInstance(supervisorInstanceId, "QUERY");
        requireSupervisor(supervisorInstanceId);
        int pageNum = pageNum(pageQuery);
        int pageSize = pageSize(pageQuery);
        String statusFilter = status == null || status.isBlank() ? null : status.toUpperCase();
        String countSql = "SELECT COUNT(*) FROM (SELECT round_no,"
            + " CASE WHEN SUM(CASE WHEN event_status IN ('PENDING','RETRY_WAIT','PROCESSING',"
            + "'WAIT_PREDECESSOR','WAIT_EXTERNAL_TRIGGER','MANUAL_PENDING') THEN 1 ELSE 0 END) > 0"
            + " THEN 'OPEN' WHEN SUM(CASE WHEN event_status = 'FAILED_FINAL' THEN 1 ELSE 0 END) > 0"
            + " THEN 'FAILED' ELSE 'COMPLETED' END AS inferred_status"
            + " FROM iws_process_event WHERE instance_id = ?"
            + (roundNo == null ? "" : " AND round_no = ?")
            + " GROUP BY round_no) t WHERE (? IS NULL OR t.inferred_status = ?)";
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(supervisorInstanceId);
        if (roundNo != null) {
            countArgs.add(roundNo);
        }
        countArgs.add(statusFilter);
        countArgs.add(statusFilter);
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, countArgs.toArray());
        String pageSql = "SELECT round_no, MIN(create_time) AS started_at,"
            + " MAX(COALESCE(ended_at, update_time)) AS ended_at, COUNT(*) AS event_count,"
            + " SUM(CASE WHEN event_status IN ('SUCCESS','SKIPPED','NOT_SELECTED') THEN 1 ELSE 0 END) AS success_count,"
            + " SUM(CASE WHEN event_status = 'FAILED_FINAL' THEN 1 ELSE 0 END) AS failed_count,"
            + " SUM(CASE WHEN event_status IN ('PENDING','RETRY_WAIT','PROCESSING',"
            + "'WAIT_PREDECESSOR','WAIT_EXTERNAL_TRIGGER','MANUAL_PENDING') THEN 1 ELSE 0 END) AS open_count,"
            + " MAX(definition_version_id) AS definition_version_id,"
            + " CASE WHEN SUM(CASE WHEN event_status IN ('PENDING','RETRY_WAIT','PROCESSING',"
            + "'WAIT_PREDECESSOR','WAIT_EXTERNAL_TRIGGER','MANUAL_PENDING') THEN 1 ELSE 0 END) > 0"
            + " THEN 'OPEN' WHEN SUM(CASE WHEN event_status = 'FAILED_FINAL' THEN 1 ELSE 0 END) > 0"
            + " THEN 'FAILED' ELSE 'COMPLETED' END AS inferred_status"
            + " FROM iws_process_event WHERE instance_id = ?"
            + (roundNo == null ? "" : " AND round_no = ?")
            + " GROUP BY round_no HAVING (? IS NULL OR inferred_status = ?)"
            + " ORDER BY round_no DESC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>();
        pageArgs.add(supervisorInstanceId);
        if (roundNo != null) {
            pageArgs.add(roundNo);
        }
        pageArgs.add(statusFilter);
        pageArgs.add(statusFilter);
        pageArgs.add(pageSize);
        pageArgs.add((long) (pageNum - 1) * pageSize);
        ProcessScheduleControl control = dispatchControl(supervisorInstanceId);
        List<SupervisorRoundVO> rows = jdbcTemplate.query(pageSql,
            (rs, rowNum) -> round(rs, control, supervisorInstanceId), pageArgs.toArray());
        return PageResult.build(rows, total == null ? 0L : total);
    }

    /**
     * 分页查询调度记录。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param roundNo 轮次
     * @param childInstanceId 子计划
     * @param commandKey 命令键
     * @param action 动作
     * @param result 结果
     * @param from 起始时间
     * @param to 结束时间
     * @param pageQuery 分页
     * @return 分页
     */
    public PageResult<SupervisorDispatchRecordVO> pageDispatchRecords(long supervisorInstanceId, Long roundNo,
                                                                      Long childInstanceId, String commandKey,
                                                                      String action, String result,
                                                                      LocalDateTime from, LocalDateTime to,
                                                                      PageQuery pageQuery) {
        objectAuthorizer.authorizeInstance(supervisorInstanceId, "QUERY");
        com.innolux.iws.process.enums.AuditResult resultValue = null;
        if (result != null && !result.isBlank()) {
            try {
                resultValue = com.innolux.iws.process.enums.AuditResult.valueOf(result.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new ServiceException("非法调度结果: " + result);
            }
        }
        var wrapper = Wrappers.<ProcessActionAudit>lambdaQuery()
            .eq(ProcessActionAudit::getSupervisorInstanceId, supervisorInstanceId)
            .eq(roundNo != null, ProcessActionAudit::getRoundNo, roundNo)
            .eq(childInstanceId != null, ProcessActionAudit::getChildInstanceId, childInstanceId)
            .eq(commandKey != null && !commandKey.isBlank(), ProcessActionAudit::getCommandKey, commandKey)
            .eq(action != null && !action.isBlank(), ProcessActionAudit::getActionCode, action)
            .eq(resultValue != null, ProcessActionAudit::getResult, resultValue)
            .ge(from != null, ProcessActionAudit::getOccurredAt, from)
            .le(to != null, ProcessActionAudit::getOccurredAt, to)
            .orderByDesc(ProcessActionAudit::getOccurredAt)
            .orderByDesc(ProcessActionAudit::getId);
        Page<ProcessActionAudit> page = auditMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords().stream().map(this::dispatch).toList(), page.getTotal());
    }

    private SupervisorSummaryVO summary(ProcessInstance supervisor) {
        ProcessScheduleControl control = dispatchControl(supervisor.getId());
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        long held = controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD));
        List<String> actions = SupervisorControlService.allowedActions(supervisor, control, held);
        return new SupervisorSummaryVO(ProcessText.id(supervisor.getId()), supervisor.getSupervisorKey(),
            ProcessText.id(supervisor.getScheduleId()), supervisor.getBusinessScheduleCode(),
            supervisor.getPlanType(), supervisor.getScheduleScope(),
            supervisor.getModeSnapshot() == null ? null : supervisor.getModeSnapshot().name(),
            supervisor.getPlanStatus() == null ? null : supervisor.getPlanStatus().name(),
            control == null || control.getControlState() == null ? null : control.getControlState().name(),
            ProcessText.id(supervisor.getDefinitionVersionId()), ProcessText.id(supervisor.getInstanceVersion()),
            control == null ? null : control.getRevision(), control == null ? null : control.getRoundNo(),
            control == null || control.getRoundStatus() == null ? null : control.getRoundStatus().name(),
            definition == null ? null : definition.getActivePlanLimit(), held,
            control == null ? null : control.getDesiredState(), control == null ? null : control.getObservedState(),
            control != null && Boolean.TRUE.equals(control.getWakePending()),
            control == null ? supervisor.getExceptionSummary() : control.getErrorMessage(), actions,
            SupervisorControlService.actionReason(control, held));
    }

    private SupervisorRoundVO round(ResultSet rs, ProcessScheduleControl control, long supervisorInstanceId)
            throws SQLException {
        long roundNo = rs.getLong("round_no");
        String status = rs.getString("inferred_status");
        if (control != null && control.getRoundNo() != null && control.getRoundNo() == roundNo
            && control.getRoundStatus() != null) {
            status = control.getRoundStatus().name();
        }
        List<String> checkpoints = jdbcTemplate.queryForList(
            "SELECT a.checkpoint_json FROM iws_process_attempt a"
                + " JOIN iws_process_event e ON e.id = a.event_id"
                + " WHERE e.instance_id = ? AND e.round_no = ? AND e.event_role = 'DECISION'"
                + " AND a.checkpoint_json IS NOT NULL ORDER BY a.id DESC LIMIT 1",
            String.class, supervisorInstanceId, roundNo);
        int candidateCount = 0;
        int selectedCount = 0;
        String decisionReason = null;
        if (!checkpoints.isEmpty()) {
            SupervisorDecisionStore.Decision decision = SupervisorDecisionStore.parse(checkpoints.getFirst());
            if (decision != null) {
                candidateCount = decision.candidateCount();
                selectedCount = decision.selected().size();
                decisionReason = decision.reason();
            }
        }
        String error = control != null && control.getRoundNo() != null && control.getRoundNo() == roundNo
            ? control.getErrorMessage() : null;
        return new SupervisorRoundVO(roundNo, status,
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toLocalDateTime(),
            rs.getInt("event_count"), rs.getInt("success_count"), rs.getInt("failed_count"),
            candidateCount, selectedCount, decisionReason, ProcessText.id(rs.getLong("definition_version_id")),
            error);
    }

    private SupervisorDispatchRecordVO dispatch(ProcessActionAudit audit) {
        return new SupervisorDispatchRecordVO(ProcessText.id(audit.getId()), audit.getActionCode(),
            audit.getResult() == null ? null : audit.getResult().name(),
            ProcessText.id(audit.getSupervisorInstanceId()), audit.getRoundNo(),
            ProcessText.id(audit.getChildInstanceId()), ProcessText.id(audit.getEventId()),
            ProcessText.id(audit.getAttemptId()), audit.getCommandKey(), audit.getReason(),
            audit.getDecisionSummary(), audit.getSlotBefore(), audit.getSlotAfter(), audit.getEpoch(),
            audit.getControlRevision(), audit.getPlatformJobId(), audit.getOccurredAt());
    }

    private LocalDateTime latestActivity(long supervisorInstanceId) {
        ProcessScheduleControl control = dispatchControl(supervisorInstanceId);
        return control == null ? null : control.getLastActivityAt();
    }

    private ProcessScheduleControl dispatchControl(long supervisorInstanceId) {
        return controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
    }

    private ProcessInstance requireSupervisor(long supervisorInstanceId) {
        ProcessInstance supervisor = instanceMapper.selectById(supervisorInstanceId);
        if (supervisor == null || supervisor.getPlanRole() != PlanRole.SUPERVISOR) {
            throw new ServiceException("监督实例不存在: " + supervisorInstanceId);
        }
        return supervisor;
    }

    private static PlanMode parseMode(String mode) {
        try {
            return PlanMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("非法监督模式: " + mode);
        }
    }

    private static PlanStatus parseStatus(String status) {
        try {
            return PlanStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("非法监督状态: " + status);
        }
    }

    private static ProcessAccessTarget childTarget(ProcessInstance supervisor) {
        return new ProcessAccessTarget(supervisor.getId(), null, supervisor.getScheduleId(),
            supervisor.getPlanType(), supervisor.getSupervisorKey(), supervisor.getTaskKey(),
            supervisor.getScheduleScope(), "QUERY");
    }

    private static int pageNum(PageQuery pageQuery) {
        return pageQuery == null || pageQuery.getPageNum() == null || pageQuery.getPageNum() <= 0
            ? 1 : pageQuery.getPageNum();
    }

    private static int pageSize(PageQuery pageQuery) {
        return pageQuery == null || pageQuery.getPageSize() == null || pageQuery.getPageSize() <= 0
            ? 20 : Math.min(pageQuery.getPageSize(), MAX_PAGE_SIZE);
    }
}
