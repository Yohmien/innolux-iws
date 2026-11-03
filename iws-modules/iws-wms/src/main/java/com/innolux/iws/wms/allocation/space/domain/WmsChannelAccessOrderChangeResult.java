/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道访问顺序
 * @Version      : V1.0
 * @Description  : 返回访问顺序变更后的稳定成员身份和修订号；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

import java.util.List;

/**
 * 通道成员访问顺序变更结果。
 *
 * @param channelId 通道 ID
 * @param channelRevision 更新后的通道修订号
 * @param members 已更新成员
 */
public record WmsChannelAccessOrderChangeResult(
    long channelId,
    int channelRevision,
    List<MemberOrderResult> members
) {

    public WmsChannelAccessOrderChangeResult {
        members = List.copyOf(members);
    }

    /**
     * 单个成员的更新结果。
     *
     * @param memberId 成员 ID
     * @param storageId 储位 ID
     * @param oldAccessOrder 原访问顺序
     * @param accessOrder 当前访问顺序
     * @param revision 当前成员修订号
     */
    public record MemberOrderResult(
        long memberId,
        long storageId,
        int oldAccessOrder,
        int accessOrder,
        int revision
    ) {
    }
}
