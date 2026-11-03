/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道可达性
 * @Version      : V1.0
 * @Description  : 只按显式成员、访问顺序和阻挡边计算单通道传递可达性；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.reachability;

import com.innolux.iws.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单通道显式阻挡图可达性内核。
 *
 * <p>输入必须来自同一通道的显式成员和显式阻挡边。访问顺序只决定稳定输出顺序，
 * 不从地址文本、深度或访问顺序推断隐式阻挡。只有当前被占用的阻挡储位会沿有向边
 * 阻断其全部后代储位。</p>
 */
@Component
public final class WmsChannelReachabilityEvaluator {

    public static final String ERROR_INVALID_INPUT = "CHANNEL_REACHABILITY_INPUT_INVALID";

    public static final String ERROR_INVALID_GRAPH = "CHANNEL_REACHABILITY_GRAPH_INVALID";

    /**
     * 计算通道可达性。
     *
     * @param members 同一通道的显式成员
     * @param edges 同一通道的显式阻挡边
     * @param occupiedBlockerStorageIds 当前被占用的阻挡储位
     * @return 可达性快照
     */
    public WmsChannelReachabilityResult evaluate(
        List<Member> members,
        List<BlockingEdge> edges,
        Set<Long> occupiedBlockerStorageIds
    ) {
        List<Member> ordered = orderedMembers(members);
        Map<Long, Member> memberById = new LinkedHashMap<>();
        Map<Long, Integer> orderByStorageId = new HashMap<>();
        Set<Integer> accessOrders = new HashSet<>();
        for (Member member : ordered) {
            if (member == null || member.storageId() <= 0 || member.accessOrder() < 0) {
                throw invalidInput("通道成员身份或访问顺序非法");
            }
            if (memberById.putIfAbsent(member.storageId(), member) != null) {
                throw invalidInput("通道成员储位重复: " + member.storageId());
            }
            if (!accessOrders.add(member.accessOrder())) {
                throw invalidInput("通道访问顺序重复: " + member.accessOrder());
            }
            orderByStorageId.put(member.storageId(), member.accessOrder());
        }

        Map<Long, Set<Long>> descendants = new LinkedHashMap<>();
        Set<BlockingEdge> edgeSet = new HashSet<>();
        if (edges != null) {
            for (BlockingEdge edge : edges) {
                if (edge == null || edge.blockerStorageId() <= 0 || edge.blockedStorageId() <= 0) {
                    throw invalidInput("阻挡边身份非法");
                }
                if (edge.blockerStorageId() == edge.blockedStorageId()) {
                    throw invalidGraph("阻挡边不允许自环: " + edge.blockerStorageId());
                }
                if (!memberById.containsKey(edge.blockerStorageId())
                    || !memberById.containsKey(edge.blockedStorageId())) {
                    throw invalidGraph("阻挡边引用不属于当前通道的储位: "
                        + edge.blockerStorageId() + " -> " + edge.blockedStorageId());
                }
                if (!edgeSet.add(edge)) {
                    throw invalidGraph("阻挡边重复: "
                        + edge.blockerStorageId() + " -> " + edge.blockedStorageId());
                }
                descendants.computeIfAbsent(edge.blockerStorageId(), ignored -> new LinkedHashSet<>())
                    .add(edge.blockedStorageId());
            }
        }
        assertAcyclic(ordered, descendants);

        Set<Long> occupied = occupiedBlockerStorageIds == null
            ? Set.of() : new LinkedHashSet<>(occupiedBlockerStorageIds);
        for (Long storageId : occupied) {
            if (storageId == null || !memberById.containsKey(storageId)) {
                throw invalidInput("活动阻挡储位不属于当前通道: " + storageId);
            }
        }

        Map<Long, Set<Long>> blockersByTarget = new HashMap<>();
        for (Long blockerStorageId : occupied) {
            markDescendants(
                blockerStorageId,
                descendants.getOrDefault(blockerStorageId, Set.of()),
                descendants,
                blockersByTarget
            );
        }

        List<Long> orderedStorageIds = ordered.stream().map(Member::storageId).toList();
        List<Long> reachable = new ArrayList<>();
        List<Long> unreachable = new ArrayList<>();
        Map<Long, List<Long>> blockers = new LinkedHashMap<>();
        Comparator<Long> byAccessOrder = Comparator.comparingInt(orderByStorageId::get);
        for (Member member : ordered) {
            if (blockersByTarget.containsKey(member.storageId())) {
                unreachable.add(member.storageId());
                blockers.put(member.storageId(), blockersByTarget.get(member.storageId()).stream()
                    .sorted(byAccessOrder)
                    .toList());
            } else {
                reachable.add(member.storageId());
            }
        }
        return new WmsChannelReachabilityResult(
            orderedStorageIds,
            reachable,
            unreachable,
            blockers
        );
    }

    private List<Member> orderedMembers(List<Member> members) {
        if (members == null) {
            throw invalidInput("通道成员不能为空");
        }
        return members.stream()
            .sorted(Comparator.comparingInt(Member::accessOrder)
                .thenComparingLong(Member::storageId))
            .toList();
    }

    private void assertAcyclic(List<Member> members, Map<Long, Set<Long>> descendants) {
        Map<Long, Integer> colors = new HashMap<>();
        for (Member member : members) {
            visit(member.storageId(), descendants, colors);
        }
    }

    private void visit(
        long storageId,
        Map<Long, Set<Long>> descendants,
        Map<Long, Integer> colors
    ) {
        int color = colors.getOrDefault(storageId, 0);
        if (color == 2) {
            return;
        }
        if (color == 1) {
            throw invalidGraph("阻挡边存在环: " + storageId);
        }
        colors.put(storageId, 1);
        for (Long child : descendants.getOrDefault(storageId, Set.of())) {
            visit(child, descendants, colors);
        }
        colors.put(storageId, 2);
    }

    private void markDescendants(
        long blockerStorageId,
        Set<Long> directChildren,
        Map<Long, Set<Long>> descendants,
        Map<Long, Set<Long>> blockersByTarget
    ) {
        Deque<Long> queue = new ArrayDeque<>(directChildren);
        Set<Long> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Long target = queue.removeFirst();
            if (!visited.add(target)) {
                continue;
            }
            blockersByTarget.computeIfAbsent(target, ignored -> new LinkedHashSet<>())
                .add(blockerStorageId);
            queue.addAll(descendants.getOrDefault(target, Set.of()));
        }
    }

    private ServiceException invalidInput(String message) {
        return new ServiceException(ERROR_INVALID_INPUT + ": " + message);
    }

    private ServiceException invalidGraph(String message) {
        return new ServiceException(ERROR_INVALID_GRAPH + ": " + message);
    }

    /**
     * 通道成员。
     *
     * @param storageId 稳定储位 ID
     * @param accessOrder 显式访问顺序
     */
    public record Member(long storageId, int accessOrder) {
    }

    /**
     * 显式阻挡边，从阻挡储位指向被阻挡储位。
     *
     * @param blockerStorageId 阻挡储位 ID
     * @param blockedStorageId 被阻挡储位 ID
     */
    public record BlockingEdge(long blockerStorageId, long blockedStorageId) {
    }
}
