/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : 分配候选的通道成员初始快照与锁内当前读；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.space;

import java.util.Collection;
import java.util.Map;

/** 通道成员快照读取端口。 */
public interface WmsAllocationSpaceReader {

    /**
     * 在加通道/储位锁前读取候选成员身份，用于发现锁域变化。
     *
     * @param storageIds 候选储位
     * @return 按储位索引的成员快照
     */
    Map<Long, Membership> readInitial(Collection<Long> storageIds);

    /**
     * 在通道和储位行锁后用当前读复核成员身份，并锁定成员行。
     *
     * @param storageIds 候选储位
     * @return 按储位索引的当前成员
     */
    Map<Long, Membership> readCurrentForUpdate(Collection<Long> storageIds);

    /** 一个储位当前的通道成员身份。 */
    record Membership(
        long storageId,
        Long memberId,
        long channelId,
        int accessOrder,
        int memberRevision,
        String channelStatus,
        int channelRevision
    ) {
    }
}
