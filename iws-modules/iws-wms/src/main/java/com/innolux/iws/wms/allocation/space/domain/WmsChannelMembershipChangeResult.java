/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道成员共锁
 * @Version      : V1.0
 * @Description  : 返回成员变更后的稳定ID、访问顺序和通道修订号；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

/**
 * 普通储位通道成员变更结果。
 *
 * @param storageId 稳定储位 ID
 * @param oldChannelId 原通道 ID
 * @param newChannelId 新通道 ID
 * @param memberId 当前成员 ID，移出后为 null
 * @param accessOrder 当前访问顺序，移出后为 null
 * @param memberRevision 当前成员修订号，移出后为 null
 * @param oldChannelRevision 原通道更新后修订号
 * @param newChannelRevision 新通道更新后修订号
 */
public record WmsChannelMembershipChangeResult(
    long storageId,
    Long oldChannelId,
    Long newChannelId,
    Long memberId,
    Integer accessOrder,
    Integer memberRevision,
    Integer oldChannelRevision,
    Integer newChannelRevision
) {
}
