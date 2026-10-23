/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 不可变计划 DAG 视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个冻结计划版本的不可变邻接图。
 */
public final class DefinitionGraph {

    private final Map<String, ProcessEventDefinition> events;

    private final Map<String, ProcessState> states;

    private final Map<String, List<ProcessEdge>> outgoing;

    private final Map<String, List<ProcessEdge>> incoming;

    private final List<String> roots;

    /**
     * 构建不可变图。
     *
     * @param eventList 事件
     * @param stateList 状态
     * @param edgeList 边
     */
    public DefinitionGraph(List<ProcessEventDefinition> eventList, List<ProcessState> stateList,
                           List<ProcessEdge> edgeList) {
        Map<String, ProcessEventDefinition> eventMap = new LinkedHashMap<>();
        for (ProcessEventDefinition event : eventList) {
            eventMap.put(event.getEventCode(), event);
        }
        Map<String, ProcessState> stateMap = new LinkedHashMap<>();
        for (ProcessState state : stateList) {
            stateMap.put(state.getStateCode(), state);
        }
        Map<String, List<ProcessEdge>> out = new LinkedHashMap<>();
        Map<String, List<ProcessEdge>> in = new LinkedHashMap<>();
        for (String code : eventMap.keySet()) {
            out.put(code, new ArrayList<>());
            in.put(code, new ArrayList<>());
        }
        for (ProcessEdge edge : edgeList) {
            if (out.containsKey(edge.getPredecessorEventCode()) && in.containsKey(edge.getSuccessorEventCode())) {
                out.get(edge.getPredecessorEventCode()).add(edge);
                in.get(edge.getSuccessorEventCode()).add(edge);
            }
        }
        this.events = Map.copyOf(eventMap);
        this.states = Map.copyOf(stateMap);
        this.outgoing = freeze(out);
        this.incoming = freeze(in);
        this.roots = in.entrySet().stream().filter(entry -> entry.getValue().isEmpty()).map(Map.Entry::getKey).toList();
    }

    /**
     * 获取事件定义。
     *
     * @param eventCode 事件编码
     * @return 定义，不存在返回 null
     */
    public ProcessEventDefinition event(String eventCode) {
        return events.get(eventCode);
    }

    /**
     * 获取状态定义。
     *
     * @param stateCode 状态编码
     * @return 状态，不存在返回 null
     */
    public ProcessState state(String stateCode) {
        return states.get(stateCode);
    }

    /**
     * 全部事件编码。
     *
     * @return 事件编码集合
     */
    public Set<String> eventCodes() {
        return events.keySet();
    }

    /**
     * 前驱边。
     *
     * @param eventCode 事件编码
     * @return 前驱边
     */
    public List<ProcessEdge> incoming(String eventCode) {
        return incoming.getOrDefault(eventCode, List.of());
    }

    /**
     * 后继边。
     *
     * @param eventCode 事件编码
     * @return 后继边
     */
    public List<ProcessEdge> outgoing(String eventCode) {
        return outgoing.getOrDefault(eventCode, List.of());
    }

    /**
     * 无入边的事件。
     *
     * @return 根事件
     */
    public List<String> roots() {
        return roots;
    }

    /**
     * 拓扑排序；存在环时返回空列表。
     *
     * @return 拓扑序
     */
    public List<String> topologicalOrder() {
        Map<String, Integer> degree = new LinkedHashMap<>();
        for (String code : events.keySet()) {
            degree.put(code, incoming.get(code).size());
        }
        Deque<String> queue = new ArrayDeque<>();
        degree.forEach((code, count) -> {
            if (count == 0) {
                queue.add(code);
            }
        });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String code = queue.removeFirst();
            order.add(code);
            for (ProcessEdge edge : outgoing.get(code)) {
                String next = edge.getSuccessorEventCode();
                int remaining = degree.merge(next, -1, Integer::sum);
                if (remaining == 0) {
                    queue.addLast(next);
                }
            }
        }
        return order.size() == events.size() ? order : List.of();
    }

    /**
     * 从起点集合可达的事件。
     *
     * @param starts 起点集合
     * @return 可达事件
     */
    public Set<String> reachableFrom(List<String> starts) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            String code = queue.removeFirst();
            if (!events.containsKey(code) || !visited.add(code)) {
                continue;
            }
            for (ProcessEdge edge : outgoing.get(code)) {
                queue.addLast(edge.getSuccessorEventCode());
            }
        }
        return visited;
    }

    private static Map<String, List<ProcessEdge>> freeze(Map<String, List<ProcessEdge>> source) {
        Map<String, List<ProcessEdge>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }
}
