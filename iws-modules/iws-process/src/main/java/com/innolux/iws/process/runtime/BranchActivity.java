/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 从已固化决策推导实例活动路径、关闭集合与汇合期待集合；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.domain.ProcessToken;
import com.innolux.iws.process.enums.BranchKind;
import com.innolux.iws.process.enums.TokenRole;
import com.innolux.iws.process.support.DefinitionGraph;
import com.innolux.iws.process.support.ProcessText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读活动路径快照。
 *
 * <p>第一版仍按固定事件图预物化全部占位事件，本类只回答“哪些节点从已选根出发仍然可达”、
 * “哪条入边仍是汇合期待项”和“该节点是否已确定不可达”。不可达节点必须关闭为
 * {@code NOT_SELECTED}，不能被当作已满足前驱。共享汇合只要仍有一条未关闭路径可达就保留。</p>
 */
final class BranchActivity {

    private final DefinitionGraph graph;

    private final long roundNo;

    private final Set<String> selectedRoots;

    private final Map<String, Set<String>> decisions;

    private final Set<String> forcedClosed;

    private final Set<String> reachable;

    /**
     * 构建活动路径快照。
     *
     * @param graph 冻结定义图
     * @param instance 计划实例
     * @param scopes 已持久决策 scope
     * @param forcedClosed 运行中确定不进入正常期待集合的事件
     * @param roundNo 监督轮次，业务计划固定为 0
     */
    BranchActivity(DefinitionGraph graph, ProcessInstance instance, List<ProcessToken> scopes,
                   Set<String> forcedClosed, long roundNo) {
        this.graph = graph;
        this.roundNo = roundNo;
        this.selectedRoots = selectedRoots(instance, graph);
        this.decisions = decisions(scopes, roundNo);
        this.forcedClosed = forcedClosed == null ? Set.of() : Set.copyOf(forcedClosed);
        this.reachable = reachable();
    }

    /**
     * 根事件是否被实例选择。
     *
     * @param eventCode 事件编码
     * @return 是否选择
     */
    boolean rootSelected(String eventCode) {
        return selectedRoots.contains(eventCode);
    }

    /**
     * 事件是否确定不可达。
     *
     * @param eventCode 事件编码
     * @return 是否关闭
     */
    boolean closed(String eventCode) {
        return forcedClosed.contains(eventCode) || !reachable.contains(eventCode);
    }

    /**
     * 当前仍期待到达的入边。
     *
     * @param eventCode 事件编码
     * @return 入边
     */
    List<ProcessEdge> expectedIncoming(String eventCode) {
        if (closed(eventCode)) {
            return List.of();
        }
        List<ProcessEdge> expected = new ArrayList<>();
        for (ProcessEdge edge : graph.incoming(eventCode)) {
            if (closed(edge.getPredecessorEventCode()) || blocked(edge)) {
                continue;
            }
            expected.add(edge);
        }
        return List.copyOf(expected);
    }

    /**
     * 汇合期待到达键。
     *
     * @param eventCode 汇合事件编码
     * @return 稳定到达键
     */
    Set<String> expectedJoinKeys(String eventCode) {
        Set<String> keys = new LinkedHashSet<>();
        for (ProcessEdge edge : expectedIncoming(eventCode)) {
            keys.add(arrivalKey(edge));
        }
        return keys;
    }

    /**
     * 正常期待集合，不含已关闭节点。
     *
     * @return 事件编码
     */
    Set<String> expectedCodes() {
        Set<String> result = new LinkedHashSet<>();
        for (String code : graph.eventCodes()) {
            if (!closed(code)) {
                result.add(code);
            }
        }
        return result;
    }

    /**
     * 某个 XOR 决策节点已选分支。
     *
     * @param decisionNodeCode 决策节点
     * @return 已选分支键
     */
    Set<String> selectedBranches(String decisionNodeCode) {
        return decisions.getOrDefault(roundNo + ":" + decisionNodeCode, Set.of());
    }

    private boolean blocked(ProcessEdge edge) {
        if (edge.getBranchKind() != BranchKind.XOR) {
            return false;
        }
        Set<String> selected = decisions.get(roundNo + ":" + edge.getPredecessorEventCode());
        return selected != null && !selected.contains(edge.getBranchKey());
    }

    private Set<String> reachable() {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(selectedRoots);
        while (!queue.isEmpty()) {
            String code = queue.removeFirst();
            if (!graph.eventCodes().contains(code) || !visited.add(code)) {
                continue;
            }
            for (ProcessEdge edge : graph.outgoing(code)) {
                if (!blocked(edge)) {
                    queue.addLast(edge.getSuccessorEventCode());
                }
            }
        }
        return Set.copyOf(visited);
    }

    private static Map<String, Set<String>> decisions(List<ProcessToken> scopes, long roundNo) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (ProcessToken scope : scopes) {
            if (scope.getTokenRole() != TokenRole.SCOPE || !"".equals(scope.getBranchKey())) {
                continue;
            }
            String scopeKey = scope.getScopeKey();
            if (scopeKey == null || !scopeKey.startsWith("FORK:")) {
                continue;
            }
            if (scope.getDecisionNodeCode() == null || scope.getSelectedKeys() == null) {
                continue;
            }
            long scopeRound = scope.getRoundNo() == null ? 0L : scope.getRoundNo();
            result.put(scopeRound + ":" + scope.getDecisionNodeCode(), split(scope.getSelectedKeys()));
        }
        return Map.copyOf(result);
    }

    private static Set<String> selectedRoots(ProcessInstance instance, DefinitionGraph graph) {
        Set<String> roots = new LinkedHashSet<>(graph.roots());
        String raw = instance.getSelectedRootSet();
        if (raw == null || raw.isBlank()) {
            return Set.copyOf(roots);
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            if (!value.isBlank()) {
                selected.add(value.trim());
            }
        }
        if (selected.isEmpty()) {
            throw new ServiceException("selectedRootSet 不能为空集合");
        }
        for (String code : selected) {
            if (!roots.contains(code)) {
                throw new ServiceException("selectedRootSet 不是定义根事件: " + code);
            }
        }
        return Set.copyOf(selected);
    }

    /**
     * 决策 scope 键；业务轮次为 0。
     *
     * @param roundNo 轮次
     * @param eventCode 决策节点
     * @return scope 键
     */
    static String forkScopeKey(long roundNo, String eventCode) {
        return roundNo > 0 ? "FORK:" + roundNo + ":" + eventCode : "FORK:" + eventCode;
    }

    /**
     * 汇合 scope 键；业务轮次为 0。
     *
     * @param roundNo 轮次
     * @param eventCode 汇合节点
     * @return scope 键
     */
    static String joinScopeKey(long roundNo, String eventCode) {
        return roundNo > 0 ? "JOIN:" + roundNo + ":" + eventCode : "JOIN:" + eventCode;
    }

    /**
     * 最近上游分支/汇合 scope，用于嵌套隔离。
     *
     * @param graph 定义图
     * @param eventCode 当前节点
     * @param roundNo 轮次
     * @return 父 scope 键
     */
    static String parentScopeKey(DefinitionGraph graph, String eventCode, long roundNo) {
        Map<String, Integer> distance = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (ProcessEdge edge : graph.incoming(eventCode)) {
            queue.addLast(edge.getPredecessorEventCode());
        }
        int depth = 0;
        while (!queue.isEmpty()) {
            depth++;
            int size = queue.size();
            for (int index = 0; index < size; index++) {
                String code = queue.removeFirst();
                if (distance.putIfAbsent(code, depth) != null) {
                    continue;
                }
                for (ProcessEdge edge : graph.incoming(code)) {
                    queue.addLast(edge.getPredecessorEventCode());
                }
            }
        }
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : distance.entrySet()) {
            String code = entry.getKey();
            if (!isFork(graph, code) && !isJoin(graph, code)) {
                continue;
            }
            int value = entry.getValue();
            if (value < bestDepth || (value == bestDepth && (best == null || code.compareTo(best) < 0))) {
                best = code;
                bestDepth = value;
            }
        }
        if (best == null) {
            return "ROOT";
        }
        return isFork(graph, best) ? forkScopeKey(roundNo, best) : joinScopeKey(roundNo, best);
    }

    /**
     * 是否为分支节点。
     *
     * @param graph 定义图
     * @param eventCode 事件编码
     * @return 是否有多条出边
     */
    static boolean isFork(DefinitionGraph graph, String eventCode) {
        return graph.outgoing(eventCode).size() > 1;
    }

    /**
     * 是否为汇合节点。
     *
     * @param graph 定义图
     * @param eventCode 事件编码
     * @return 是否有多条入边
     */
    static boolean isJoin(DefinitionGraph graph, String eventCode) {
        return graph.incoming(eventCode).size() > 1;
    }

    /**
     * 汇合到达键，按入边身份唯一。
     *
     * @param edge 入边
     * @return 稳定键
     */
    static String arrivalKey(ProcessEdge edge) {
        String branchKey = edge.getBranchKey() == null ? "" : edge.getBranchKey();
        return edge.getPredecessorEventCode() + "|" + branchKey;
    }

    /**
     * 冻结决策规则版本；输入变化必须读回冲突，不能静默改道。
     *
     * @param edges 候选边
     * @return 版本摘要
     */
    static String ruleVersion(List<ProcessEdge> edges) {
        List<String> parts = new ArrayList<>();
        for (ProcessEdge edge : edges) {
            parts.add(edge.getBranchKey() + "|"
                + (edge.getConditionKey() == null ? "" : edge.getConditionKey()) + "@"
                + (edge.getConditionVersion() == null ? "" : edge.getConditionVersion()) + "|"
                + Boolean.TRUE.equals(edge.getDefaultBranch()));
        }
        parts.sort(String::compareTo);
        return ProcessText.sha256(String.join(";", parts)).substring(0, 32);
    }

    /**
     * 逗号集合解析。
     *
     * @param value 文本
     * @return 集合
     */
    static Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return Set.copyOf(result);
    }
}
