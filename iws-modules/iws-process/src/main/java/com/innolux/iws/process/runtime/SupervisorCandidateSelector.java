/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督候选唯一排序/公平窗口/只读 rank 快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.BusinessSchedule;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.SlotState;
import com.innolux.iws.process.mapper.BusinessScheduleMapper;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 监督候选的唯一排序实现。决定动作和只读查询必须复用本类，不能各自重写公平算法。
 *
 * <p>snapshotCandidates 返回的是监督内部完整候选视图；HTTP 查询必须先按对象/数据范围
 * 过滤，或使用带 visibility 的重载，不能把完整集合直接序列化。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorCandidateSelector {

    private static final String WAIT_PLAN_SLOT = "WAIT_PLAN_SLOT";

    private static final Comparator<ProcessInstance> READY_ORDER = Comparator
        .comparing(ProcessInstance::getCreateTime,
            Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(ProcessInstance::getId);

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final PlanDefinitionMapper definitionMapper;

    private final BusinessScheduleMapper scheduleMapper;

    /**
     * 构造选择器。
     *
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param definitionMapper 定义 Mapper
     * @param scheduleMapper 业务调度 Mapper
     */
    public SupervisorCandidateSelector(ProcessInstanceMapper instanceMapper,
                                       ProcessScheduleControlMapper controlMapper,
                                       PlanDefinitionMapper definitionMapper,
                                       BusinessScheduleMapper scheduleMapper) {
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.definitionMapper = definitionMapper;
        this.scheduleMapper = scheduleMapper;
    }

    /**
     * 只读计算一个监督的候选快照。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param limit 单轮选择上限；小于等于 0 时使用冻结 roundBatchLimit
     * @return 完整候选快照
     */
    public CandidateSnapshot snapshotCandidates(long supervisorInstanceId, int limit) {
        ProcessInstance supervisor = instanceMapper.selectById(supervisorInstanceId);
        if (supervisor == null || supervisor.getPlanRole() != PlanRole.SUPERVISOR) {
            throw new ServiceException("监督实例不存在: " + supervisorInstanceId);
        }
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        SupervisorRules.requireSupervisorDefinition(definition);
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
        if (control == null) {
            throw new ServiceException("监督控制行不存在: " + supervisorInstanceId);
        }
        return snapshotCandidates(supervisor, definition, control, limit);
    }

    /**
     * 只读计算一个监督的候选快照。
     *
     * @param supervisor 监督实例
     * @param definition 冻结定义
     * @param control 控制行
     * @param limit 单轮选择上限；小于等于 0 时使用冻结 roundBatchLimit
     * @return 完整候选快照
     */
    public CandidateSnapshot snapshotCandidates(ProcessInstance supervisor, PlanDefinition definition,
                                                ProcessScheduleControl control, int limit) {
        BusinessSchedule schedule = scheduleMapper.selectById(supervisor.getScheduleId());
        int window = definition.getMode() == PlanMode.QUEUE
            ? SupervisorRules.fairnessWindow(definition, schedule) : 1;
        int current = control.getFairnessCount() == null ? 0 : control.getFairnessCount();
        boolean fairnessTurn = definition.getMode() == PlanMode.QUEUE
            && SupervisorRules.fairnessTurn(current, window);
        long held = controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, com.innolux.iws.process.enums.ScheduleControlScope.PLAN_SLOT)
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessScheduleControl::getSlotState, SlotState.HELD));
        int planLimit = SupervisorRules.activePlanLimit(definition);
        int free = (int) Math.max(0, planLimit - held);
        List<ProcessInstance> all = instanceMapper.selectList(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getSupervisorInstanceId, supervisor.getId())
            .eq(ProcessInstance::getPlanRole, PlanRole.BUSINESS)
            .eq(ProcessInstance::getPlanStatus, PlanStatus.CREATED)
            .eq(ProcessInstance::getHoldStart, Boolean.FALSE)
            .orderByDesc(ProcessInstance::getInstancePriority)
            .orderByAsc(ProcessInstance::getCreateTime)
            .orderByAsc(ProcessInstance::getId));
        List<ProcessInstance> ordered = fairOrder(all, fairnessTurn);
        ProcessInstance reserved = fairnessTurn && !ordered.isEmpty()
            && SupervisorRules.normal(ordered.getFirst()) ? ordered.getFirst() : null;
        int batchLimit = limit > 0 ? limit
            : (definition.getRoundBatchLimit() == null ? free : definition.getRoundBatchLimit());
        int selectionCap = Math.min(free, Math.max(0, batchLimit));
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            ProcessInstance child = ordered.get(index);
            int rank = index + 1;
            boolean eligible = free > 0 && rank <= free;
            boolean selected = rank <= selectionCap;
            candidates.add(new Candidate(child.getId(), child.getPlanType(), child.getScheduleScope(),
                child.getInstancePriority(), child.getCreateTime(), rank, eligible,
                eligible ? null : WAIT_PLAN_SLOT, selected, reserved != null && reserved.getId().equals(child.getId())));
        }
        String reason = candidates.isEmpty() ? "NO_CANDIDATE" : (free <= 0 ? "SLOT_FULL"
            : (reserved != null ? "FAIRNESS" : "PRIORITY"));
        int eligibleCount = Math.min(candidates.size(), free);
        return new CandidateSnapshot(supervisor.getId(), value(control.getRoundNo()), planLimit,
            (int) held, free, window, current, fairnessTurn, reason, eligibleCount,
            candidates.size(), List.copyOf(candidates));
    }

    /**
     * HTTP 查询使用的可见候选视图；只返回 visibility 放行的对象，计数同步收窄，rank 保留全局序位。
     *
     * @param supervisorInstanceId 监督实例 ID
     * @param limit 单轮选择上限
     * @param visibility 可见性谓词
     * @return 可见候选快照
     */
    public CandidateSnapshot snapshotCandidates(long supervisorInstanceId, int limit,
                                                Predicate<Candidate> visibility) {
        CandidateSnapshot full = snapshotCandidates(supervisorInstanceId, limit);
        List<Candidate> visible = full.candidates().stream()
            .filter(visibility == null ? item -> false : visibility).toList();
        int eligible = (int) visible.stream().filter(Candidate::eligible).count();
        return new CandidateSnapshot(full.supervisorInstanceId(), full.roundNo(), full.limit(), full.held(),
            full.free(), full.fairnessWindow(), full.fairnessCount(), full.fairnessTurn(), full.reason(),
            eligible, visible.size(), visible);
    }

    private static List<ProcessInstance> fairOrder(List<ProcessInstance> all, boolean fairnessTurn) {
        if (!fairnessTurn) {
            return all;
        }
        ProcessInstance oldestNormal = all.stream().filter(SupervisorRules::normal)
            .min(READY_ORDER).orElse(null);
        if (oldestNormal == null) {
            return all;
        }
        List<ProcessInstance> ordered = new ArrayList<>(all.size());
        ordered.add(oldestNormal);
        for (ProcessInstance candidate : all) {
            if (!candidate.getId().equals(oldestNormal.getId())) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 候选快照。
     *
     * @param supervisorInstanceId 监督实例
     * @param roundNo 轮次
     * @param limit 名额上限
     * @param held 已占名额
     * @param free 剩余名额
     * @param fairnessWindow 公平窗口 K
     * @param fairnessCount 当前窗口计数
     * @param fairnessTurn 是否末位保底
     * @param reason 选择原因
     * @param eligibleCount 可准入数
     * @param candidateCount 候选数
     * @param candidates 候选顺序
     */
    public record CandidateSnapshot(long supervisorInstanceId, long roundNo, int limit, int held, int free,
                                    int fairnessWindow, int fairnessCount, boolean fairnessTurn,
                                    String reason, int eligibleCount, int candidateCount,
                                    List<Candidate> candidates) {
    }

    /**
     * 单个候选的只读序位。
     *
     * @param instanceId 子计划 ID
     * @param planType 计划类型
     * @param scheduleScope 范围
     * @param priority 实例优先级
     * @param readyAt 就绪时间
     * @param rank 全局候选序位
     * @param eligible 当前是否有名额
     * @param blockReason 阻塞原因
     * @param selected 本轮是否被选中
     * @param fairnessReserved 是否公平保底位
     */
    public record Candidate(long instanceId, String planType, String scheduleScope, Integer priority,
                            LocalDateTime readyAt, int rank, boolean eligible, String blockReason,
                            boolean selected, boolean fairnessReserved) {
    }
}
