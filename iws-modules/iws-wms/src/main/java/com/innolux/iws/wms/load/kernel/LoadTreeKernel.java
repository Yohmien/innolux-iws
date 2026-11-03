/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 校验并解析无持久化、无锁语义的多根装载森林；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 纯装载森林校验入口。输入变更整组失败，不返回截断树，也不自动拆载或缩树。
 * 本类型只证明内存拓扑关系，不证明储位占用、事务、锁或容量分配。
 */
public final class LoadTreeKernel {
    private LoadTreeKernel() {
    }

    public static LoadTree parse(List<LoadNode> sourceNodes, List<LoadEdge> sourceEdges) {
        return parse(sourceNodes, sourceEdges, LoadLimits.DEFAULT);
    }

    /**
     * 校验节点、显式子父关系和森林深度。
     *
     * @param sourceNodes 不可变节点快照；ID 重复会使整组失败
     * @param sourceEdges 显式 child-parent 关系；重复关系和单节点多父均会使整组失败
     * @param limits 根深度为 1 的独立深度限额
     * @return 按节点 ID、边子 ID 和叶 ID 稳定排序的只读森林
     * @throws LoadKernelException 任一节点、引用、父类型、单父、环或深度不满足时
     */
    public static LoadTree parse(List<LoadNode> sourceNodes, List<LoadEdge> sourceEdges,
                                 LoadLimits limits) {
        if (sourceNodes == null || sourceEdges == null || limits == null) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_INPUT_NULL);
        }

        List<LoadNode> nodes = new ArrayList<>(sourceNodes);
        if (nodes.stream().anyMatch(node -> node == null)) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_INPUT_NULL);
        }
        nodes.sort(Comparator.comparingLong(LoadNode::id));

        Map<Long, LoadNode> nodesById = new HashMap<>();
        Set<Long> inventoryIds = new HashSet<>();
        Set<Long> containerIds = new HashSet<>();
        for (LoadNode node : nodes) {
            if (nodesById.putIfAbsent(node.id(), node) != null
                || (node.inventoryId() != null && !inventoryIds.add(node.inventoryId()))
                || (node.containerId() != null && !containerIds.add(node.containerId()))) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_NODE_DUPLICATE);
            }
        }

        List<LoadEdge> edges = new ArrayList<>(sourceEdges);
        if (edges.stream().anyMatch(edge -> edge == null)) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_INPUT_NULL);
        }
        edges.sort(Comparator.comparingLong(LoadEdge::childId).thenComparingLong(LoadEdge::parentId));

        Set<LoadEdge> uniqueEdges = new HashSet<>();
        NavigableMap<Long, Long> parentByChild = new TreeMap<>();
        for (LoadEdge edge : edges) {
            if (!uniqueEdges.add(edge)) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_EDGE_DUPLICATE);
            }
            if (edge.childId() == edge.parentId()) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_SELF_PARENT);
            }
            Long previousParent = parentByChild.putIfAbsent(edge.childId(), edge.parentId());
            if (previousParent != null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_MULTIPLE_PARENTS);
            }
        }

        for (LoadEdge edge : edges) {
            LoadNode child = nodesById.get(edge.childId());
            LoadNode parent = nodesById.get(edge.parentId());
            if (child == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_CHILD_UNKNOWN);
            }
            if (parent == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_PARENT_UNKNOWN);
            }
            if (parent.type() == LoadNode.Type.MATERIAL) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_MATERIAL_HAS_CHILD);
            }
            // 当前节点类型只有两种；非载具父节点的唯一情形已在上方按物料有子拒绝。
        }

        rejectCycles(nodes, parentByChild);
        enforceDepth(nodes, parentByChild, limits.maxDepth());
        return new LoadTree(nodes, edges, parentByChild);
    }

    private static void rejectCycles(List<LoadNode> nodes,
                                     Map<Long, Long> parentByChild) {
        Map<Long, VisitState> states = new HashMap<>();
        for (LoadNode node : nodes) {
            List<Long> path = new ArrayList<>();
            Long current = node.id();
            while (current != null && states.getOrDefault(current, VisitState.UNVISITED)
                == VisitState.UNVISITED) {
                states.put(current, VisitState.VISITING);
                path.add(current);
                current = parentByChild.get(current);
            }
            if (current != null && states.get(current) == VisitState.VISITING) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_CYCLE);
            }
            for (Long visited : path) {
                states.put(visited, VisitState.VISITED);
            }
        }
    }

    private static void enforceDepth(List<LoadNode> nodes, Map<Long, Long> parentByChild,
                                     int maxDepth) {
        for (LoadNode node : nodes) {
            int depth = 1;
            Long current = node.id();
            while (parentByChild.containsKey(current)) {
                depth++;
                if (depth > maxDepth) {
                    throw LoadKernelException.failure(LoadKernelException.Code.LOAD_DEPTH_EXCEEDED);
                }
                current = parentByChild.get(current);
            }
        }
    }

    private enum VisitState {
        UNVISITED,
        VISITING,
        VISITED
    }
}
