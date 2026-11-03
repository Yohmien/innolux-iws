/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道成员共锁
 * @Version      : V1.0
 * @Description  : 冻结普通储位加入、移出或跨通道变更的期望版本；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

import java.time.LocalDateTime;

/**
 * 普通储位通道成员变更命令。
 *
 * @param storageId 稳定储位 ID
 * @param targetChannelId 目标通道 ID，移出时为 null
 * @param targetAccessOrder 目标访问顺序，移出时为 null
 * @param expectedCurrentChannelId 期望当前通道 ID，新增时为 null
 * @param expectedMemberId 期望当前成员 ID，新增时为 null
 * @param expectedMemberRevision 期望当前成员修订号，新增时为 0
 * @param expectedCurrentChannelRevision 期望当前通道修订号，新增时为 null
 * @param expectedTargetChannelRevision 期望目标通道修订号，移出时为 null
 * @param occurredAt 操作发生时间
 * @param actorId 操作者 ID
 * @param departmentId 操作部门 ID
 */
public record WmsChannelMembershipChangeCommand(
    long storageId,
    Long targetChannelId,
    Integer targetAccessOrder,
    Long expectedCurrentChannelId,
    Long expectedMemberId,
    Integer expectedMemberRevision,
    Integer expectedCurrentChannelRevision,
    Integer expectedTargetChannelRevision,
    LocalDateTime occurredAt,
    Long actorId,
    Long departmentId
) {
}
