/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道访问顺序
 * @Version      : V1.0
 * @Description  : 冻结通道成员批量访问顺序变更的身份和修订号；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通道成员访问顺序变更命令。
 *
 * @param channelId 通道 ID
 * @param expectedChannelRevision 期望通道修订号
 * @param members 待调整成员
 * @param occurredAt 操作发生时间
 * @param actorId 操作者 ID
 * @param departmentId 操作部门 ID
 */
public record WmsChannelAccessOrderChangeCommand(
    long channelId,
    int expectedChannelRevision,
    List<MemberOrder> members,
    LocalDateTime occurredAt,
    Long actorId,
    Long departmentId
) {

    public WmsChannelAccessOrderChangeCommand {
        members = members == null ? null : List.copyOf(members);
    }

    /**
     * 单个成员的访问顺序变更。
     *
     * @param memberId 成员 ID
     * @param expectedRevision 期望成员修订号
     * @param targetAccessOrder 目标访问顺序
     */
    public record MemberOrder(
        long memberId,
        int expectedRevision,
        int targetAccessOrder
    ) {
    }
}
