/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督固定图与逐候选 LOCAL 事件的幂等物化；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每轮复用冻结图，只在 DECISION 后为每个选中候选创建独立 LOCAL 事件。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class SupervisorRoundMaterializer {

    private final RuntimeDao runtimeDao;

    private final ProcessEventMapper eventMapper;

    private final ProcessTokenMapper tokenMapper;

    private final EventEligibilityService eligibilityService;

    /**
     * 构造物化器。
     *
     * @param runtimeDao 运行时 DAO
     * @param eventMapper 事件 Mapper
     * @param tokenMapper token Mapper
     * @param eligibilityService 资格服务
     */
    public SupervisorRoundMaterializer(RuntimeDao runtimeDao, ProcessEventMapper eventMapper,
                                       ProcessTokenMapper tokenMapper, EventEligibilityService eligibilityService) {
        this.runtimeDao = runtimeDao;
        this.eventMapper = eventMapper;
        this.tokenMapper = tokenMapper;
        this.eligibilityService = eligibilityService;
    }

    /**
     * 物化固定监督事件；已存在的事件不重复插入。
     *
     * @param supervisor 监督实例
     * @param definition 冻结定义
     * @param roundNo 轮次
     */
    public void materialize(ProcessInstance supervisor, PlanDefinition definition, long roundNo) {
        materializeRound(supervisor, definition, roundNo);
    }

    /**
     * 物化固定监督事件。
     *
     * @param supervisor 监督实例
     * @param definition 冻结定义
     * @param roundNo 轮次
     */
    public void materializeRound(ProcessInstance supervisor, PlanDefinition definition, long roundNo) {
        DefinitionGraph graph = runtimeDao.graph(definition.getId());
        if (graph.eventCodes().isEmpty()) {
            throw new ServiceException("监督计划未配置 DECISION/LOCAL 事件: " + definition.getId());
        }
        int templates = 0;
        for (String code : graph.eventCodes()) {
            ProcessEventDefinition eventDefinition = graph.event(code);
            requireSupervisorEvent(eventDefinition);
            if (SupervisorRules.ACTION_ADMIT.equals(eventDefinition.getLocalActionKey())) {
                templates++;
            }
        }
        if (templates > 1) {
            throw new ServiceException("监督图只能配置一个逐候选准入 LOCAL 模板");
        }
        for (String code : graph.topologicalOrder()) {
            ProcessEventDefinition eventDefinition = graph.event(code);
            if (SupervisorRules.ACTION_ADMIT.equals(eventDefinition.getLocalActionKey())) {
                continue;
            }
            String eventKey = "SUP:" + roundNo + ":" + code;
            if (exists(supervisor.getId(), eventKey)) {
                continue;
            }
            insert(supervisor, definition, roundNo, code, eventDefinition, eventKey,
                "SUP:" + roundNo, "ROOT", "SUP:" + roundNo + ":" + code, null);
        }
        recompute(supervisor.getId(), roundNo);
    }

    /**
     * 为选中候选创建独立 LOCAL 事件。
     *
     * @param supervisor 监督实例
     * @param definition 冻结定义
     * @param roundNo 轮次
     * @param decision 决策
     * @return 新建事件数
     */
    public int materializeCandidates(ProcessInstance supervisor, PlanDefinition definition, long roundNo,
                                     SupervisorDecisionStore.Decision decision) {
        if (decision == null || decision.selected().isEmpty()) {
            return 0;
        }
        DefinitionGraph graph = runtimeDao.graph(definition.getId());
        ProcessEventDefinition template = null;
        for (String code : graph.eventCodes()) {
            ProcessEventDefinition candidate = graph.event(code);
            if (SupervisorRules.ACTION_ADMIT.equals(candidate.getLocalActionKey())) {
                if (template != null) {
                    throw new ServiceException("监督图只能配置一个逐候选准入 LOCAL 模板");
                }
                template = candidate;
            }
        }
        if (template == null) {
            throw new ServiceException("监督图缺少逐候选准入 LOCAL 模板");
        }
        if (!graph.outgoing(template.getEventCode()).isEmpty()) {
            throw new ServiceException("逐候选准入 LOCAL 必须为终态事件，不能有后继");
        }
        int created = 0;
        for (Long childId : decision.selected()) {
            String eventKey = "SUP:" + roundNo + ":" + template.getEventCode() + ":C" + childId;
            if (exists(supervisor.getId(), eventKey)) {
                continue;
            }
            insert(supervisor, definition, roundNo, template.getEventCode(), template, eventKey,
                "SUP:" + roundNo + ":C" + childId, "C" + childId,
                "SUP:" + roundNo + ":" + template.getEventCode() + ":C" + childId, childId);
            created++;
        }
        recompute(supervisor.getId(), roundNo);
        return created;
    }

    private void insert(ProcessInstance supervisor, PlanDefinition definition, long roundNo, String eventCode,
                        ProcessEventDefinition eventDefinition, String eventKey, String branchIdentity,
                        String branchKey, String scopeKey, Long childId) {
        ProcessToken token = new ProcessToken();
        token.setInstanceId(supervisor.getId());
        token.setTokenRole(TokenRole.BRANCH);
        token.setTokenStatus(TokenStatus.ACTIVE);
        token.setStateCode(eventDefinition.getSourceStateCode());
        token.setDecisionNodeCode(eventCode);
        token.setParentScopeKey("ROOT");
        token.setScopeKey(scopeKey);
        token.setBranchKey(branchKey);
        token.setBarrierClosed(Boolean.FALSE);
        token.setTokenVersion(1L);
        token.setRoundNo(roundNo);
        tokenMapper.insert(token);

        ProcessEvent event = new ProcessEvent();
        event.setInstanceId(supervisor.getId());
        event.setTokenId(token.getId());
        event.setDefinitionVersionId(definition.getId());
        event.setEventDefinitionId(eventDefinition.getId());
        event.setEventCode(eventCode);
        event.setEventKey(eventKey);
        event.setRoundNo(roundNo);
        event.setBranchIdentity(branchIdentity);
        event.setEventRole(eventDefinition.getEventRole());
        event.setEventDirection(eventDefinition.getEventDirection());
        event.setSystemCode(eventDefinition.getSystemCode());
        event.setCallContractKey(eventDefinition.getCallContractKey());
        event.setCallContractVersion(eventDefinition.getCallContractVersion());
        event.setLocalActionKey(eventDefinition.getLocalActionKey());
        event.setLocalActionVersion(eventDefinition.getLocalActionVersion());
        event.setEffectKey(eventDefinition.getEffectKey());
        event.setLocalEffectKey(eventDefinition.getLocalEffectKey());
        event.setSourceStateCode(eventDefinition.getSourceStateCode());
        event.setTargetStateCode(eventDefinition.getTargetStateCode());
        event.setEventStatus(EventStatus.WAIT_PREDECESSOR);
        event.setWaitReason(WaitReason.PREDECESSOR);
        event.setInputHash(ProcessText.sha256(eventKey + ":" + eventCode + ":" + branchIdentity));
        event.setSent(Boolean.FALSE);
        event.setPayloadReady(Boolean.TRUE);
        event.setRetryCount(0);
        event.setRecoveryRequested(Boolean.FALSE);
        event.setExecutionPurpose(ExecutionPurpose.NORMAL);
        event.setPriority(eventDefinition.getPriority() == null ? 0 : eventDefinition.getPriority());
        event.setLeaseEpoch(0L);
        event.setEventVersion(1L);
        event.setIdempotencyKey(SupervisorRules.stableKey("SUP", String.valueOf(supervisor.getId()),
            String.valueOf(roundNo), eventCode, childId == null ? "ROOT" : "C" + childId,
            eventDefinition.getLocalActionVersion() == null ? "1" : eventDefinition.getLocalActionVersion(),
            eventDefinition.getLocalEffectKey() == null ? "NONE" : eventDefinition.getLocalEffectKey()));
        eventMapper.insert(event);
    }

    private void recompute(long supervisorInstanceId, long roundNo) {
        List<ProcessEvent> events = eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorInstanceId)
            .eq(ProcessEvent::getRoundNo, roundNo)
            .orderByAsc(ProcessEvent::getId));
        for (ProcessEvent event : events) {
            eligibilityService.recompute(event.getId());
        }
    }

    private boolean exists(long supervisorInstanceId, String eventKey) {
        return eventMapper.selectCount(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, supervisorInstanceId)
            .eq(ProcessEvent::getEventKey, eventKey)) > 0;
    }

    private static void requireSupervisorEvent(ProcessEventDefinition definition) {
        if (definition.getEventRole() != EventRole.DECISION && definition.getEventRole() != EventRole.LOCAL) {
            throw new ServiceException("监督图只允许 DECISION/LOCAL 事件: " + definition.getEventCode());
        }
        if (definition.getEventDirection() != EventDirection.INTERNAL) {
            throw new ServiceException("监督事件方向必须为 INTERNAL: " + definition.getEventCode());
        }
    }
}
