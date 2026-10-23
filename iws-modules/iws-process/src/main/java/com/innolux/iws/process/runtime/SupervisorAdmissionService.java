/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督准入窄端口实现
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.api.SupervisorAdmissionPort;
import com.innolux.iws.process.api.SupervisorRoundLease;
import com.innolux.iws.process.api.SupervisorSlot;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.ControlState;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.RoundStatus;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 监督轮次与计划名额窄端口实现。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorAdmissionService implements SupervisorAdmissionPort {

    private final SupervisorRuntimeService runtimeService;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessScheduleControlMapper controlMapper;

    private final PlanDefinitionMapper definitionMapper;

    /**
     * 构造服务。
     *
     * @param runtimeService 监督运行服务
     * @param instanceMapper 实例 Mapper
     * @param controlMapper 控制 Mapper
     * @param definitionMapper 定义 Mapper
     */
    public SupervisorAdmissionService(SupervisorRuntimeService runtimeService,
                                      ProcessInstanceMapper instanceMapper,
                                      ProcessScheduleControlMapper controlMapper,
                                      PlanDefinitionMapper definitionMapper) {
        this.runtimeService = runtimeService;
        this.instanceMapper = instanceMapper;
        this.controlMapper = controlMapper;
        this.definitionMapper = definitionMapper;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SupervisorRoundLease openExclusive(long supervisorInstanceId, String commandKey, long maxDurationMs) {
        return runtimeService.beginRound(supervisorInstanceId, commandKey, maxDurationMs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String precheck(long supervisorInstanceId, PlanMode requiredMode) {
        ProcessInstance supervisor = instanceMapper.selectById(supervisorInstanceId);
        if (supervisor == null || supervisor.getPlanRole() != PlanRole.SUPERVISOR) {
            return "NOT_READY";
        }
        ProcessScheduleControl control = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getSupervisorInstanceId, supervisorInstanceId)
            .eq(ProcessScheduleControl::getDuty, SupervisorRules.DUTY_DISPATCH));
        if (control == null) {
            return "NOT_READY";
        }
        if (control.getControlState() == ControlState.MANUAL_PAUSED) {
            return "SUPERVISOR_PAUSED";
        }
        PlanDefinition definition = definitionMapper.selectById(supervisor.getDefinitionVersionId());
        if (definition == null || definition.getMode() == null) {
            return "NOT_READY";
        }
        if (requiredMode != null && definition.getMode() != requiredMode) {
            return "MODE_MISMATCH";
        }
        if (control.getRoundStatus() == RoundStatus.OPEN && control.getLeaseUntil() != null
            && control.getLeaseUntil().isAfter(LocalDateTime.now())) {
            return "BUSY";
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SupervisorSlot admit(long supervisorInstanceId, SupervisorRoundLease lease, long childInstanceId) {
        return runtimeService.admitWithLease(supervisorInstanceId, lease, childInstanceId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean release(long supervisorInstanceId, long childInstanceId, String reason) {
        return runtimeService.releaseSlot(supervisorInstanceId, childInstanceId, reason);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean close(SupervisorRoundLease lease, RoundStatus status, String reason) {
        return runtimeService.closeRound(lease, status, reason);
    }
}
