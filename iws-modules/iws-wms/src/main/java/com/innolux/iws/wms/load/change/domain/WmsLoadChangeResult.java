/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 装拆事实
 * @Version      : V1.0
 * @Description  : 返回装拆后持久根位置、容量占用和历史事实；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.change.domain;

import java.util.List;
import java.util.Map;

/**
 * 已提交装拆事实。
 *
 * @param occupancyEffectId 占用事务头 ID
 * @param effectKey 领域效果键
 * @param relationHistoryIds 本次追加的关系历史 ID
 * @param activeOccupancyIds 装拆后活动占用明细 ID
 * @param closedOccupancyIds 本次关闭的占用明细 ID
 * @param rootNodeIds 装拆后的全部根节点 ID
 * @param storageRootCounts 各物理储位根节点数量
 */
public record WmsLoadChangeResult(
    long occupancyEffectId,
    String effectKey,
    List<Long> relationHistoryIds,
    List<Long> activeOccupancyIds,
    List<Long> closedOccupancyIds,
    List<Long> rootNodeIds,
    Map<Long, Integer> storageRootCounts
) {
    public WmsLoadChangeResult {
        relationHistoryIds = List.copyOf(relationHistoryIds);
        activeOccupancyIds = List.copyOf(activeOccupancyIds);
        closedOccupancyIds = List.copyOf(closedOccupancyIds);
        rootNodeIds = List.copyOf(rootNodeIds);
        storageRootCounts = Map.copyOf(storageRootCounts);
    }
}
