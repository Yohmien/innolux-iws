/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 运行时短查询与图装载；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessAttempt;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessState;
import com.innolux.iws.process.domain.ProcessSystemContext;
import com.innolux.iws.process.domain.ProcessSystemPolicy;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessAttemptMapper;
import com.innolux.iws.process.mapper.ProcessEdgeMapper;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessStateMapper;
import com.innolux.iws.process.mapper.ProcessSystemContextMapper;
import com.innolux.iws.process.mapper.ProcessSystemPolicyMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.process.support.DefinitionGraphBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 运行时只读装载入口。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class RuntimeDao {

    private final PlanDefinitionMapper definitionMapper;

    private final ProcessStateMapper stateMapper;

    private final ProcessEventDefinitionMapper eventDefinitionMapper;

    private final ProcessEdgeMapper edgeMapper;

    private final ProcessSystemPolicyMapper policyMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final ProcessTokenMapper tokenMapper;

    private final ProcessEventMapper eventMapper;

    private final ProcessAttemptMapper attemptMapper;

    private final ProcessSystemContextMapper contextMapper;

    private final DefinitionGraphBuilder graphBuilder;

    /**
     * 构造 DAO。
     *
     * @param definitionMapper 定义 Mapper
     * @param stateMapper 状态 Mapper
     * @param eventDefinitionMapper 事件定义 Mapper
     * @param edgeMapper 边 Mapper
     * @param policyMapper 系统模式 Mapper
     * @param instanceMapper 实例 Mapper
     * @param tokenMapper token Mapper
     * @param eventMapper 事件 Mapper
     * @param attemptMapper 尝试 Mapper
     * @param contextMapper 系统上下文 Mapper
     * @param graphBuilder 图构建器
     */
    public RuntimeDao(PlanDefinitionMapper definitionMapper, ProcessStateMapper stateMapper,
                      ProcessEventDefinitionMapper eventDefinitionMapper, ProcessEdgeMapper edgeMapper,
                      ProcessSystemPolicyMapper policyMapper, ProcessInstanceMapper instanceMapper,
                      ProcessTokenMapper tokenMapper, ProcessEventMapper eventMapper,
                      ProcessAttemptMapper attemptMapper, ProcessSystemContextMapper contextMapper,
                      DefinitionGraphBuilder graphBuilder) {
        this.definitionMapper = definitionMapper;
        this.stateMapper = stateMapper;
        this.eventDefinitionMapper = eventDefinitionMapper;
        this.edgeMapper = edgeMapper;
        this.policyMapper = policyMapper;
        this.instanceMapper = instanceMapper;
        this.tokenMapper = tokenMapper;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.contextMapper = contextMapper;
        this.graphBuilder = graphBuilder;
    }

    /**
     * 获取实例。
     *
     * @param id 实例 ID
     * @return 实例
     */
    public ProcessInstance instance(long id) {
        ProcessInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new ServiceException("计划实例不存在: " + id);
        }
        return instance;
    }

    /**
     * 获取事件。
     *
     * @param id 事件 ID
     * @return 事件
     */
    public ProcessEvent event(long id) {
        ProcessEvent event = eventMapper.selectById(id);
        if (event == null) {
            throw new ServiceException("计划事件不存在: " + id);
        }
        return event;
    }

    /**
     * 获取尝试。
     *
     * @param id 尝试 ID
     * @return 尝试
     */
    public ProcessAttempt attempt(long id) {
        ProcessAttempt attempt = attemptMapper.selectById(id);
        if (attempt == null) {
            throw new ServiceException("事件尝试不存在: " + id);
        }
        return attempt;
    }

    /**
     * 获取 token。
     *
     * @param id token ID
     * @return token
     */
    public ProcessToken token(long id) {
        ProcessToken token = tokenMapper.selectById(id);
        if (token == null) {
            throw new ServiceException("计划 token 不存在: " + id);
        }
        return token;
    }

    /**
     * 实例事件集合。
     *
     * @param instanceId 实例 ID
     * @return 事件
     */
    public List<ProcessEvent> events(long instanceId) {
        return eventMapper.selectList(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, instanceId).orderByAsc(ProcessEvent::getId));
    }

    /**
     * 按实例和事件编码查询占位事件。
     *
     * @param instanceId 实例 ID
     * @param eventCode 事件编码
     * @return 事件，不存在返回 null
     */
    public ProcessEvent findEvent(long instanceId, String eventCode) {
        return eventMapper.selectOne(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, instanceId)
            .eq(ProcessEvent::getEventCode, eventCode)
            .orderByAsc(ProcessEvent::getId)
            .last("LIMIT 1"));
    }

    /**
     * 按轮次查询事件，监督轮次必须使用该入口。
     *
     * @param instanceId 实例 ID
     * @param eventCode 事件编码
     * @param roundNo 轮次号
     * @return 事件，不存在返回 null
     */
    public ProcessEvent findEvent(long instanceId, String eventCode, long roundNo) {
        return eventMapper.selectOne(Wrappers.<ProcessEvent>lambdaQuery()
            .eq(ProcessEvent::getInstanceId, instanceId)
            .eq(ProcessEvent::getEventCode, eventCode)
            .eq(ProcessEvent::getRoundNo, roundNo)
            .last("LIMIT 1"));
    }

    /**
     * 装载定义图。
     *
     * @param versionId 定义版本 ID
     * @return 图
     */
    public DefinitionGraph graph(long versionId) {
        List<ProcessState> states = stateMapper.selectList(Wrappers.<ProcessState>lambdaQuery()
            .eq(ProcessState::getDefinitionVersionId, versionId).orderByAsc(ProcessState::getSortOrder));
        List<ProcessEventDefinition> events = eventDefinitionMapper.selectList(
            Wrappers.<ProcessEventDefinition>lambdaQuery()
                .eq(ProcessEventDefinition::getDefinitionVersionId, versionId)
                .orderByAsc(ProcessEventDefinition::getEventCode));
        List<ProcessEdge> edges = edgeMapper.selectList(Wrappers.<ProcessEdge>lambdaQuery()
            .eq(ProcessEdge::getDefinitionVersionId, versionId).orderByAsc(ProcessEdge::getId));
        return graphBuilder.build(events, states, edges);
    }

    /**
     * 获取计划定义。
     *
     * @param versionId 版本 ID
     * @return 定义
     */
    public PlanDefinition definition(long versionId) {
        PlanDefinition definition = definitionMapper.selectById(versionId);
        if (definition == null) {
            throw new ServiceException("计划定义不存在: " + versionId);
        }
        return definition;
    }

    /**
     * 获取实例系统上下文。
     *
     * @param instanceId 实例 ID
     * @param systemCode 系统编码
     * @return 上下文，不存在返回 null
     */
    public ProcessSystemContext systemContext(long instanceId, String systemCode) {
        return contextMapper.selectOne(Wrappers.<ProcessSystemContext>lambdaQuery()
            .eq(ProcessSystemContext::getInstanceId, instanceId)
            .eq(ProcessSystemContext::getSystemCode, systemCode));
    }

    /**
     * 查询版本系统模式。
     *
     * @param versionId 版本 ID
     * @return 系统模式
     */
    public List<ProcessSystemPolicy> policies(long versionId) {
        return policyMapper.selectList(Wrappers.<ProcessSystemPolicy>lambdaQuery()
            .eq(ProcessSystemPolicy::getDefinitionVersionId, versionId)
            .orderByAsc(ProcessSystemPolicy::getSystemCode));
    }
}
