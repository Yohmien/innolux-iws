/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道可达性
 * @Version      : V1.0
 * @Description  : 单个通道按显式访问顺序和阻挡边计算的可达结果；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.reachability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个通道的可达性快照。
 *
 * @param orderedStorageIds 按显式访问顺序排列的全部储位
 * @param reachableStorageIds 当前未被活动阻挡储位阻断的储位
 * @param unreachableStorageIds 当前不可达的储位
 * @param occupiedBlockersByUnreachableStorage 每个不可达储位对应的活动阻挡储位
 */
public record WmsChannelReachabilityResult(
    List<Long> orderedStorageIds,
    List<Long> reachableStorageIds,
    List<Long> unreachableStorageIds,
    Map<Long, List<Long>> occupiedBlockersByUnreachableStorage
) {

    public WmsChannelReachabilityResult {
        orderedStorageIds = List.copyOf(orderedStorageIds);
        reachableStorageIds = List.copyOf(reachableStorageIds);
        unreachableStorageIds = List.copyOf(unreachableStorageIds);
        Map<Long, List<Long>> blockers = new LinkedHashMap<>();
        occupiedBlockersByUnreachableStorage.forEach(
            (storageId, values) -> blockers.put(storageId, List.copyOf(values)));
        occupiedBlockersByUnreachableStorage = Collections.unmodifiableMap(blockers);
    }

    /**
     * 判断储位是否可达。
     *
     * @param storageId 储位 ID
     * @return 储位在通道内且不可达时为 false
     */
    public boolean reachable(long storageId) {
        return reachableStorageIds.contains(storageId);
    }

    /**
     * 读取不可达储位的活动阻挡来源。
     *
     * @param storageId 储位 ID
     * @return 按访问顺序排列的活动阻挡储位
     */
    public List<Long> occupiedBlockers(long storageId) {
        return occupiedBlockersByUnreachableStorage.getOrDefault(storageId, List.of());
    }
}
