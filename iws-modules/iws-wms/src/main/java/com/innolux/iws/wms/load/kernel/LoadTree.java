/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 保存按ID稳定的只读装载森林、根路径和物料叶索引；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/** 只读装载森林；所有外部列表与映射均由解析时快照重建。 */
public final class LoadTree {
    private final List<LoadNode> nodes;
    private final List<LoadEdge> edges;
    private final NavigableMap<Long, LoadNode> nodesById;
    private final NavigableMap<Long, Long> parentByChild;
    private final List<Long> rootIds;
    private final List<Long> materialLeafIds;
    private final NavigableMap<Long, List<Long>> materialLeafIdsByRoot;

    LoadTree(List<LoadNode> nodes, List<LoadEdge> edges,
             NavigableMap<Long, Long> parentByChild) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.parentByChild = Collections.unmodifiableNavigableMap(new TreeMap<>(parentByChild));

        NavigableMap<Long, LoadNode> nodeIndex = new TreeMap<>();
        List<Long> leaves = new ArrayList<>();
        for (LoadNode node : nodes) {
            nodeIndex.put(node.id(), node);
            if (node.type() == LoadNode.Type.MATERIAL) {
                leaves.add(node.id());
            }
        }
        this.nodesById = Collections.unmodifiableNavigableMap(nodeIndex);
        this.materialLeafIds = List.copyOf(leaves);

        List<Long> roots = new ArrayList<>();
        NavigableMap<Long, TreeSet<Long>> leavesByRoot = new TreeMap<>();
        for (LoadNode node : nodes) {
            long rootId = rootIdOf(node.id());
            if (node.id() == rootId) {
                roots.add(rootId);
            }
            savesLeaf(leavesByRoot, rootId, node);
        }
        this.rootIds = List.copyOf(roots);
        NavigableMap<Long, List<Long>> readOnlyLeavesByRoot = new TreeMap<>();
        leavesByRoot.forEach((rootId, ids) -> readOnlyLeavesByRoot.put(rootId, List.copyOf(ids)));
        this.materialLeafIdsByRoot = Collections.unmodifiableNavigableMap(readOnlyLeavesByRoot);
    }

    private static void savesLeaf(NavigableMap<Long, TreeSet<Long>> leavesByRoot, long rootId,
                                  LoadNode node) {
        if (node.type() == LoadNode.Type.MATERIAL) {
            leavesByRoot.computeIfAbsent(rootId, ignored -> new TreeSet<>()).add(node.id());
        }
    }

    public List<LoadNode> nodes() {
        return nodes;
    }

    public List<LoadEdge> edges() {
        return edges;
    }

    public Optional<LoadNode> node(long nodeId) {
        return Optional.ofNullable(nodesById.get(nodeId));
    }

    public List<Long> rootIds() {
        return rootIds;
    }

    public List<Long> materialLeafIds() {
        return materialLeafIds;
    }

    public NavigableMap<Long, List<Long>> materialLeafIdsByRoot() {
        return materialLeafIdsByRoot;
    }

    public int topLevelSlotCount() {
        return rootIds.size();
    }

    /** 返回从指定节点走向根节点的 ID 序列；根节点路径仅含自身。 */
    public List<Long> pathToRoot(long nodeId) {
        if (!nodesById.containsKey(nodeId)) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_NODE_UNKNOWN);
        }
        List<Long> path = new ArrayList<>();
        Long current = nodeId;
        while (current != null) {
            path.add(current);
            current = parentByChild.get(current);
        }
        return List.copyOf(path);
    }

    public long rootIdOf(long nodeId) {
        List<Long> path = pathToRoot(nodeId);
        return path.getLast();
    }

}
