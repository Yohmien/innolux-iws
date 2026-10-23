/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.2
 * @Description  : XOR/AND 分支统一选择、输入摘要与版本冲突单赢；
 * @ ModifyRecord : V1.0 新建；V1.1 稳定 scope 键、重试读回、输入/规则摘要冲突；V1.2 实例锁统一分支写入锁序；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.enums.TokenStatus;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessTokenMapper;
import com.innolux.iws.process.support.ProcessText;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一个决策节点在一个 scope 内只生成一次有效选择。
 *
 * <p>首次决策保存冻结输入摘要和规则摘要；重试或并发输家只读回既有结果。输入或规则不同
 * 必须显式冲突，不能按遍历顺序改道。</p>
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class BranchSelector {

    private static final String SCOPE_STATE = "SCOPE";

    private final ProcessTokenMapper tokenMapper;

    private final ProcessInstanceMapper instanceMapper;

    private final CapabilityRegistry capabilityRegistry;

    /**
     * 构造选择器。
     *
     * @param tokenMapper token Mapper
     * @param instanceMapper 实例 Mapper，作为分支写入的稳定串行锚点
     * @param capabilityRegistry 能力注册表
     */
    public BranchSelector(ProcessTokenMapper tokenMapper, ProcessInstanceMapper instanceMapper,
                          CapabilityRegistry capabilityRegistry) {
        this.tokenMapper = tokenMapper;
        this.instanceMapper = instanceMapper;
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 选择一次业务分支并持久化决策。
     *
     * @param context 分支上下文
     * @param edges 候选边
     * @return 选中分支键
     */
    @Transactional(rollbackFor = Exception.class)
    public Selection select(BranchContext context, List<ProcessEdge> edges) {
        return select(context, edges, "ROOT", 0L);
    }

    /**
     * 选择一次分支并持久化决策。
     *
     * @param context 分支上下文
     * @param edges 候选边
     * @param parentScopeKey 父 scope
     * @param roundNo 监督轮次，业务计划为 0
     * @return 选中分支键
     */
    @Transactional(rollbackFor = Exception.class)
    public Selection select(BranchContext context, List<ProcessEdge> edges, String parentScopeKey, long roundNo) {
        if (edges == null || edges.isEmpty()) {
            throw new ServiceException("分支节点缺少候选边: " + context.decisionNodeCode());
        }
        String scopeKey = ProcessText.required(context.scopeKey(), "decisionScopeKey");
        String ruleVersion = BranchActivity.ruleVersion(edges);
        lockInstance(context.instanceId());
        ProcessToken existing = lockScope(context.instanceId(), scopeKey);
        if (existing != null && decided(existing)) {
            return reuse(existing, context, ruleVersion);
        }
        Set<String> selected = choose(context, edges);
        if (existing != null) {
            return decideExisting(existing, context, edges, selected, parentScopeKey, ruleVersion, roundNo);
        }
        ProcessToken scope = newScope(context, edges, selected, parentScopeKey, scopeKey, ruleVersion, roundNo);
        try {
            int rows = tokenMapper.insert(scope);
            if (rows != 1) {
                throw new ServiceException("分支决策写入失败: " + scopeKey);
            }
            return new Selection(selected, false);
        } catch (DuplicateKeyException ex) {
            ProcessToken winner = lockScope(context.instanceId(), scopeKey);
            if (winner == null) {
                throw new ServiceException("分支决策并发写入后读回失败: " + scopeKey);
            }
            return reuse(winner, context, ruleVersion);
        }
    }

    private ProcessToken lockScope(long instanceId, String scopeKey) {
        return tokenMapper.selectOne(Wrappers.<ProcessToken>lambdaQuery()
            .eq(ProcessToken::getInstanceId, instanceId)
            .eq(ProcessToken::getScopeKey, scopeKey)
            .eq(ProcessToken::getBranchKey, "")
            .last("FOR UPDATE"));
    }

    /**
     * 锁住实例行，统一分支写入的全局锁序为实例、scope、事件。
     *
     * <p>首次 scope 创建若并发执行“先查空再插入”，两个事务会各持同一索引间隙锁后
     * 互等插入意向锁。实例行在挂载时已经存在，先锁它即可让同一实例的分支写入串行；
     * 不同实例仍可并行。</p>
     *
     * @param instanceId 实例 ID
     */
    private void lockInstance(long instanceId) {
        ProcessInstance instance = instanceMapper.selectOne(Wrappers.<ProcessInstance>lambdaQuery()
            .eq(ProcessInstance::getId, instanceId)
            .last("FOR UPDATE"));
        if (instance == null) {
            throw new ServiceException("分支决策实例不存在: " + instanceId);
        }
    }

    private Selection decideExisting(ProcessToken existing, BranchContext context, List<ProcessEdge> edges,
                                     Set<String> selected, String parentScopeKey, String ruleVersion, long roundNo) {
        long version = existing.getTokenVersion() == null ? 0L : existing.getTokenVersion();
        String parent = parentScopeKey == null || parentScopeKey.isBlank() ? "ROOT" : parentScopeKey;
        int rows = tokenMapper.update(null, Wrappers.<ProcessToken>lambdaUpdate()
            .eq(ProcessToken::getId, existing.getId())
            .eq(ProcessToken::getTokenVersion, version)
            .isNull(ProcessToken::getSelectedKeys)
            .set(ProcessToken::getDecisionInputHash, context.inputHash())
            .set(ProcessToken::getRuleVersion, ruleVersion)
            .set(ProcessToken::getSelectedKeys, csv(selected))
            .set(ProcessToken::getExpectedKeys, expectedCsv(edges))
            .set(ProcessToken::getParentScopeKey, parent)
            .set(ProcessToken::getRoundNo, roundNo)
            .set(ProcessToken::getTokenVersion, version + 1)
            .set(ProcessToken::getUpdateTime, LocalDateTime.now()));
        if (rows == 1) {
            return new Selection(selected, false);
        }
        ProcessToken latest = lockScope(context.instanceId(), context.scopeKey());
        if (latest != null && decided(latest)) {
            return reuse(latest, context, ruleVersion);
        }
        throw new ServiceException("分支决策并发写入失败: " + context.scopeKey());
    }

    private static boolean decided(ProcessToken token) {
        return token.getSelectedKeys() != null && !token.getSelectedKeys().isBlank();
    }

    private Selection reuse(ProcessToken existing, BranchContext context, String ruleVersion) {
        if (!Objects.equals(existing.getDecisionInputHash(), context.inputHash())) {
            throw new ServiceException("分支决策输入已变化，需人工修订: " + context.decisionNodeCode());
        }
        if (!Objects.equals(existing.getRuleVersion(), ruleVersion)) {
            throw new ServiceException("分支规则版本已变化，需人工修订: " + context.decisionNodeCode());
        }
        Set<String> selected = BranchActivity.split(existing.getSelectedKeys());
        if (selected.isEmpty()) {
            throw new ServiceException("分支决策缺少已选集合，需人工核对: " + context.decisionNodeCode());
        }
        return new Selection(selected, true);
    }

    private Set<String> choose(BranchContext context, List<ProcessEdge> edges) {
        boolean xor = edges.stream().allMatch(edge -> edge.getBranchKind() == BranchKind.XOR);
        boolean and = edges.stream().allMatch(edge -> edge.getBranchKind() == BranchKind.AND);
        if (!xor && !and) {
            throw new ServiceException("分支节点必须全部为 XOR 或 AND: " + context.decisionNodeCode());
        }
        Set<String> branchKeys = new LinkedHashSet<>();
        for (ProcessEdge edge : edges) {
            if (edge.getBranchKey() == null || edge.getBranchKey().isBlank()) {
                throw new ServiceException("分支边缺少 branchKey: " + context.decisionNodeCode());
            }
            if (!branchKeys.add(edge.getBranchKey())) {
                throw new ServiceException("同一分支节点 branchKey 重复: " + context.decisionNodeCode()
                    + " / " + edge.getBranchKey());
            }
        }
        if (!xor) {
            return Set.copyOf(branchKeys);
        }
        List<ProcessEdge> matched = new ArrayList<>();
        for (ProcessEdge edge : edges) {
            if (edge.getConditionKey() == null || edge.getConditionKey().isBlank()) {
                continue;
            }
            if (capabilityRegistry.condition(edge.getConditionKey(), edge.getConditionVersion())
                .matches(context.facts())) {
                matched.add(edge);
            }
        }
        if (matched.isEmpty()) {
            List<ProcessEdge> defaults = edges.stream()
                .filter(edge -> Boolean.TRUE.equals(edge.getDefaultBranch())).toList();
            if (defaults.size() != 1) {
                throw new ServiceException("XOR 无命中且默认分支不唯一: " + context.decisionNodeCode());
            }
            matched = defaults;
        }
        if (matched.size() != 1) {
            throw new ServiceException("XOR 同时命中多条分支: " + context.decisionNodeCode());
        }
        return Set.of(matched.getFirst().getBranchKey());
    }

    private ProcessToken newScope(BranchContext context, List<ProcessEdge> edges, Set<String> selected,
                                  String parentScopeKey, String scopeKey, String ruleVersion, long roundNo) {
        ProcessToken scope = new ProcessToken();
        scope.setInstanceId(context.instanceId());
        scope.setTokenRole(TokenRole.SCOPE);
        scope.setTokenStatus(TokenStatus.ACTIVE);
        scope.setStateCode(SCOPE_STATE);
        scope.setDecisionNodeCode(context.decisionNodeCode());
        scope.setParentScopeKey(parentScopeKey == null || parentScopeKey.isBlank() ? "ROOT" : parentScopeKey);
        scope.setScopeKey(scopeKey);
        scope.setBranchKey("");
        scope.setDecisionInputHash(context.inputHash());
        scope.setRuleVersion(ruleVersion);
        scope.setSelectedKeys(csv(selected));
        scope.setExpectedKeys(expectedCsv(edges));
        scope.setBarrierClosed(Boolean.FALSE);
        scope.setTokenVersion(1L);
        scope.setRoundNo(roundNo);
        scope.setUpdateTime(LocalDateTime.now());
        return scope;
    }

    private static String expectedCsv(List<ProcessEdge> edges) {
        List<String> expected = new ArrayList<>();
        for (ProcessEdge edge : edges) {
            expected.add(edge.getBranchKey());
        }
        expected.sort(String::compareTo);
        return String.join(",", expected);
    }

    private static String csv(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return String.join(",", sorted);
    }

    /**
     * 分支选择结果。
     *
     * @param selectedBranchKeys 选中分支
     * @param reused 是否复用既有决策
     */
    public record Selection(Set<String> selectedBranchKeys, boolean reused) {
    }
}
