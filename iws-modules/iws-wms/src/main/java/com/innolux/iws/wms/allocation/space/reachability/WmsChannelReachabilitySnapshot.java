/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道可达性
 * @Version      : V1.0
 * @Description  : 通道成员、阻挡边、活动占用和可达结果的只读快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.reachability;

import java.util.List;

/**
 * 单个通道的可达性读取快照。
 *
 * @param channelId 通道 ID
 * @param channelCode 通道稳定编码
 * @param status 通道状态
 * @param channelRevision 通道修订号
 * @param members 显式成员
 * @param edges 当前通道涉及的显式阻挡边
 * @param activeOccupancies 当前活动占用
 * @param reachability 显式阻挡图可达结果
 * @param snapshotHash 规范快照 SHA-256
 */
public record WmsChannelReachabilitySnapshot(
    long channelId,
    String channelCode,
    String status,
    int channelRevision,
    List<MemberFact> members,
    List<EdgeFact> edges,
    List<OccupancyFact> activeOccupancies,
    WmsChannelReachabilityResult reachability,
    String snapshotHash
) {

    public WmsChannelReachabilitySnapshot {
        members = List.copyOf(members);
        edges = List.copyOf(edges);
        activeOccupancies = List.copyOf(activeOccupancies);
    }

    /**
     * 持久化成员事实。
     *
     * @param memberId 成员 ID
     * @param storageId 储位 ID
     * @param accessOrder 显式访问顺序
     * @param revision 成员修订号
     */
    public record MemberFact(
        long memberId,
        long storageId,
        int accessOrder,
        int revision
    ) {
    }

    /**
     * 持久化阻挡边事实。
     *
     * @param accessEdgeId 阻挡边 ID
     * @param blockerStorageId 阻挡储位 ID
     * @param blockedStorageId 被阻挡储位 ID
     * @param revision 阻挡边修订号
     */
    public record EdgeFact(
        long accessEdgeId,
        long blockerStorageId,
        long blockedStorageId,
        int revision
    ) {
    }

    /**
     * 活动占用事实。
     *
     * @param occupancyId 占用明细 ID
     * @param storageId 储位 ID
     * @param rootNodeId 装载根节点 ID
     * @param revision 占用修订号
     */
    public record OccupancyFact(
        long occupancyId,
        long storageId,
        long rootNodeId,
        int revision
    ) {
    }
}
