/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划终态、失败停留、期限停止与名额收口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.api.ImmediateExecutionVO;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.AuditResult;
import com.innolux.iws.process.enums.EffectResult;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 以实例行锁仲裁完成与期限竞争，保证一个命令只形成一个最终事实。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateOutcomeService {

    private final RuntimeDao runtimeDao;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final PlanActivationService activationService;

    private final ImmediateFailureContractRegistry failureContracts;

    private final ImmediateCommandReceiptStore receiptStore;

    /**
     * 构造收口服务。
     *
     * @param runtimeDao 运行时 DAO
     * @param instanceMapper 实例 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param activationService 计划激活与名额释放
     * @param failureContracts 安全失败合同
     * @param receiptStore 回执存储
     */
    public ImmediateOutcomeService(RuntimeDao runtimeDao, ProcessInstanceMapper instanceMapper,
                                   ProcessAttemptMapper attemptMapper, PlanActivationService activationService,
                                   ImmediateFailureContractRegistry failureContracts,
                                   ImmediateCommandReceiptStore receiptStore) {
        this.runtimeDao = runtimeDao;
        this.instanceMapper = instanceMapper;
        this.attemptMapper = attemptMapper;
        this.activationService = activationService;
        this.failureContracts = failureContracts;
        this.receiptStore = receiptStore;
    }

    /**
     * 收口驱动结果。
     *
     * @param admission 准入
     * @param driveResult 驱动结果
     * @return 当前回执
     */
    @Transactional(rollbackFor = Exception.class)
    public ImmediateExecutionVO finalize(ImmediateAdmissionService.Admission admission,
                                         ImmediateExecutionDriver.DriveResult driveResult) {
        ProcessInstance instance = lockInstance(admission.instance().getId());
        if (driveResult.outcome() == ImmediateExecutionDriver.Outcome.TIMEOUT) {
            return timeoutLocked(admission, instance);
        }
        if (driveResult.outcome() == ImmediateExecutionDriver.Outcome.SUCCEEDED) {
            if (instance.getPlanStatus() == PlanStatus.COMPLETED) {
                return append(admission, instance, "SUCCEEDED", "计划已完成", "SUCCEEDED", false,
                    AuditResult.SUCCESS);
            }
            return markHold(admission, instance, "RUN_COMPLETED_WITHOUT_PLAN_TERMINAL",
                "驱动已结束但计划未收敛为 COMPLETED");
        }
        if (driveResult.outcome() == ImmediateExecutionDriver.Outcome.UNKNOWN) {
            setExceptionHold(instance, "存在未知副作用，等待显式对账");
            return append(admission, instance, "RESULT_UNKNOWN", "结果未知，已保留名额等待对账",
                effectOf(instance), true, AuditResult.UNKNOWN);
        }
        if (driveResult.outcome() == ImmediateExecutionDriver.Outcome.FAILED) {
            return finalizeFailure(admission, instance);
        }
        if (instance.getPlanStatus() == PlanStatus.COMPLETED) {
            return append(admission, instance, "SUCCEEDED", "计划已完成", "SUCCEEDED", false,
                AuditResult.SUCCESS);
        }
        if (instance.getPlanStatus() == PlanStatus.FAILED_FINAL) {
            return append(admission, instance, "FAILED", "计划已确定失败", effectOf(instance), false,
                AuditResult.FAILED);
        }
        return markHold(admission, instance, "IMMEDIATE_DRIVER_BLOCKED", driveResult.message());
    }

    /**
     * 到期停止新发送并保留原事实。
     *
     * @param admission 准入
     * @return 当前回执
     */
    @Transactional(rollbackFor = Exception.class)
    public ImmediateExecutionVO timeout(ImmediateAdmissionService.Admission admission) {
        ProcessInstance instance = lockInstance(admission.instance().getId());
        return timeoutLocked(admission, instance);
    }

    /**
     * 驱动线程异常时保留名额与现场。
     *
     * @param admission 准入
     * @param message 基础设施说明
     * @return 当前回执
     */
    @Transactional(rollbackFor = Exception.class)
    public ImmediateExecutionVO infrastructureFailure(ImmediateAdmissionService.Admission admission,
                                                      String message) {
        ProcessInstance instance = lockInstance(admission.instance().getId());
        return markHold(admission, instance, "IMMEDIATE_INFRASTRUCTURE_FAILURE", message);
    }

    /**
     * 期限返回后的晚到事实只做证据收敛，不启动任何新事件。
     *
     * @param admission 准入
     */
    @Transactional(rollbackFor = Exception.class)
    public void reconcileLate(ImmediateAdmissionService.Admission admission) {
        ProcessInstance instance = lockInstance(admission.instance().getId());
        List<ProcessEvent> events = runtimeDao.events(instance.getId());
        if (events.stream().anyMatch(event -> event.getEventStatus() == EventStatus.PROCESSING
            || event.getEffectResult() == EffectResult.UNKNOWN)) {
            return;
        }
        if (instance.getPlanStatus() == PlanStatus.COMPLETED) {
            append(admission, instance, "SUCCEEDED", "期限后证据确认计划已完成", "SUCCEEDED", false,
                AuditResult.SUCCESS);
            return;
        }
        if (instance.getPlanStatus() == PlanStatus.FAILED_FINAL) {
            append(admission, instance, "FAILED", "期限后证据确认计划已安全失败", effectOf(instance), false,
                AuditResult.FAILED);
            return;
        }
        boolean allSuccess = events.stream().allMatch(event -> event.getEventStatus() == EventStatus.SUCCESS
            || event.getEventStatus() == EventStatus.SKIPPED
            || event.getEventStatus() == EventStatus.NOT_SELECTED);
        if (allSuccess) {
            markCompleted(admission, instance);
            return;
        }
        finalizeFailure(admission, instance);
    }

    private ImmediateExecutionVO timeoutLocked(ImmediateAdmissionService.Admission admission,
                                               ProcessInstance instance) {
        if (instance.getPlanStatus() == PlanStatus.COMPLETED) {
            return append(admission, instance, "SUCCEEDED", "计划已完成，期限不覆盖确定成功",
                "SUCCEEDED", false, AuditResult.SUCCESS);
        }
        if (instance.getPlanStatus() == PlanStatus.FAILED_FINAL) {
            return append(admission, instance, "FAILED", "计划已确定失败，期限不覆盖确定失败",
                effectOf(instance), false, AuditResult.FAILED);
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, instance.getInstanceVersion())
            .set(ProcessInstance::getStopRequested, Boolean.TRUE)
            .set(ProcessInstance::getPlanStatus, PlanStatus.EXCEPTION_HOLD)
            .set(ProcessInstance::getExceptionSummary, "执行期限已到，已停止新事件发送")
            .set(ProcessInstance::getInstanceVersion, instance.getInstanceVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (rows != 1) {
            ProcessInstance latest = lockInstance(instance.getId());
            if (latest.getPlanStatus() == PlanStatus.COMPLETED) {
                return append(admission, latest, "SUCCEEDED", "计划已完成，期限不覆盖确定成功",
                    "SUCCEEDED", false, AuditResult.SUCCESS);
            }
            if (latest.getPlanStatus() == PlanStatus.FAILED_FINAL) {
                return append(admission, latest, "FAILED", "计划已确定失败，期限不覆盖确定失败",
                    effectOf(latest), false, AuditResult.FAILED);
            }
            instance = latest;
        }
        return append(admission, instance, "RESPONSE_TIMEOUT",
            "执行期限已到，原命令仍可按 commandKey 查询", effectOf(instance), true, AuditResult.UNKNOWN);
    }

    private ImmediateExecutionVO finalizeFailure(ImmediateAdmissionService.Admission admission,
                                                 ProcessInstance instance) {
        List<ProcessEvent> events = runtimeDao.events(instance.getId());
        if (events.stream().anyMatch(event -> event.getEffectResult() == EffectResult.UNKNOWN
            || event.getEventStatus() == EventStatus.PROCESSING)) {
            setExceptionHold(instance, "确定失败前仍有在途或未知副作用");
            return append(admission, instance, "RESULT_UNKNOWN", "存在在途或未知结果，不能安全终结",
                effectOf(instance), true, AuditResult.UNKNOWN);
        }
        if (!safeFailureContracts(instance, events) || !hasRuntimeEvidence(events)) {
            return markHold(admission, instance, "SAFE_FAILURE_CONTRACT_VIOLATED",
                "业务失败但缺少可信安全收口证据");
        }
        LocalDateTime now = LocalDateTime.now();
        if (instance.getPlanStatus() != PlanStatus.FAILED_FINAL) {
            int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
                .eq(ProcessInstance::getId, instance.getId())
                .eq(ProcessInstance::getInstanceVersion, instance.getInstanceVersion())
                .set(ProcessInstance::getPlanStatus, PlanStatus.FAILED_FINAL)
                .set(ProcessInstance::getExceptionSummary, null)
                .set(ProcessInstance::getEndedAt, now)
                .set(ProcessInstance::getInstanceVersion, instance.getInstanceVersion() + 1)
                .set(ProcessInstance::getUpdateTime, now));
            if (rows != 1) {
                return markHold(admission, lockInstance(instance.getId()), "FINALIZE_VERSION_CONFLICT",
                    "失败收口版本竞争失败");
            }
            instance.setPlanStatus(PlanStatus.FAILED_FINAL);
            instance.setEndedAt(now);
            instance.setInstanceVersion(instance.getInstanceVersion() + 1);
        }
        activationService.releaseSlot(instance.getId());
        return append(admission, instance, "FAILED", "业务失败，已安全终结并保留先前成功步骤",
            effectOf(instance), false, AuditResult.FAILED);
    }

    private void markCompleted(ImmediateAdmissionService.Admission admission, ProcessInstance instance) {
        LocalDateTime now = LocalDateTime.now();
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, instance.getInstanceVersion())
            .set(ProcessInstance::getPlanStatus, PlanStatus.COMPLETED)
            .set(ProcessInstance::getExceptionSummary, null)
            .set(ProcessInstance::getEndedAt, now)
            .set(ProcessInstance::getInstanceVersion, instance.getInstanceVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (rows != 1) {
            return;
        }
        instance.setPlanStatus(PlanStatus.COMPLETED);
        instance.setEndedAt(now);
        instance.setInstanceVersion(instance.getInstanceVersion() + 1);
        activationService.releaseSlot(instance.getId());
        append(admission, instance, "SUCCEEDED", "期限后证据确认计划已完成", "SUCCEEDED", false,
            AuditResult.SUCCESS);
    }

    private ImmediateExecutionVO markHold(ImmediateAdmissionService.Admission admission, ProcessInstance instance,
                                          String code, String message) {
        setExceptionHold(instance, message);
        return append(admission, instance, "FAILED_HOLD", message + " [" + code + "]",
            effectOf(instance), true, AuditResult.FAILED);
    }

    private void setExceptionHold(ProcessInstance instance, String message) {
        if (instance.getPlanStatus() == PlanStatus.EXCEPTION_HOLD) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = instanceMapper.update(null, Wrappers.<ProcessInstance>lambdaUpdate()
            .eq(ProcessInstance::getId, instance.getId())
            .eq(ProcessInstance::getInstanceVersion, instance.getInstanceVersion())
            .set(ProcessInstance::getPlanStatus, PlanStatus.EXCEPTION_HOLD)
            .set(ProcessInstance::getExceptionSummary, message)
            .set(ProcessInstance::getInstanceVersion, instance.getInstanceVersion() + 1)
            .set(ProcessInstance::getUpdateTime, now));
        if (rows == 1) {
            instance.setPlanStatus(PlanStatus.EXCEPTION_HOLD);
            instance.setExceptionSummary(message);
            instance.setInstanceVersion(instance.getInstanceVersion() + 1);
        }
    }

    private boolean safeFailureContracts(ProcessInstance instance, List<ProcessEvent> events) {
        DefinitionGraph graph = runtimeDao.graph(instance.getDefinitionVersionId());
        for (ProcessEvent event : events) {
            if (event.getEffectResult() != EffectResult.FAILED) {
                continue;
            }
            ProcessEventDefinition definition = graph.event(event.getEventCode());
            ImmediateFailureContract contract = definition == null ? null : failureContracts.find(definition);
            if (contract == null || !contract.safeToFinalizeOnDeterministicFailure()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRuntimeEvidence(List<ProcessEvent> events) {
        for (ProcessEvent event : events) {
            if (event.getEventStatus() == EventStatus.SUCCESS || event.getEventStatus() == EventStatus.SKIPPED) {
                if (event.getLastAttemptId() == null) {
                    return false;
                }
                ProcessAttempt attempt = attemptMapper.selectById(event.getLastAttemptId());
                if (attempt == null || attempt.getCheckpointStatus() == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private ImmediateExecutionVO append(ImmediateAdmissionService.Admission admission, ProcessInstance instance,
                                        String resultCode, String message, String effectResult,
                                        boolean reconciliationRequired, AuditResult auditResult) {
        if (!receiptStore.latestHasResult(admission.command().scheduleId(), admission.command().commandKey(),
            resultCode)) {
            receiptStore.append(admission.command(), admission.supervisor().getId(), instance.getId(),
                admission.roundNo(), resultCode, message, effectResult, reconciliationRequired,
                receiptStore.startedAt(admission.command().scheduleId(), admission.command().commandKey()),
                LocalDateTime.now(), auditResult);
        }
        return receiptStore.findView(admission.command().scheduleId(), admission.command().commandKey());
    }

    private String effectOf(ProcessInstance instance) {
        return runtimeDao.events(instance.getId()).stream()
            .filter(event -> event.getEffectResult() != null)
            .reduce((left, right) -> right)
            .map(event -> event.getEffectResult().name()).orElse(null);
    }

    private ProcessInstance lockInstance(long instanceId) {
        ProcessInstance instance = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getId, instanceId)
            .last("FOR UPDATE"));
        if (instance == null) {
            throw new IllegalStateException("即时计划实例不存在: " + instanceId);
        }
        return instance;
    }
}
