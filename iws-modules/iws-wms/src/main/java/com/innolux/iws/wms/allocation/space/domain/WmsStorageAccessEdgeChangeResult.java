/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 阻挡边
 * @Version      : V1.0
 * @Description  : 返回阻挡边身份及全部受影响通道修订号；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

import java.util.List;

/**
 * 储位阻挡边变更结果。
 *
 * @param accessEdgeId 阻挡边 ID
 * @param blockerStorageId 阻挡储位 ID
 * @param blockedStorageId 被阻挡储位 ID
 * @param edgeRevision 新增时为 1，删除时为被删除修订号
 * @param removed 是否已删除
 * @param channelRevisions 受影响通道修订号
 */
public record WmsStorageAccessEdgeChangeResult(
    long accessEdgeId,
    long blockerStorageId,
    long blockedStorageId,
    int edgeRevision,
    boolean removed,
    List<ChannelRevision> channelRevisions
) {

    public WmsStorageAccessEdgeChangeResult {
        channelRevisions = List.copyOf(channelRevisions);
    }

    /**
     * 受影响通道更新后的修订号。
     *
     * @param channelId 通道 ID
     * @param revision 更新后的修订号
     */
    public record ChannelRevision(long channelId, int revision) {
    }
}
