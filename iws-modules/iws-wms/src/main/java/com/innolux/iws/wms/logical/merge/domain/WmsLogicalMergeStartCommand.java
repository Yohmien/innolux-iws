/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 冻结目标作用域、来源范围、兼容范围和固定批大小；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.domain;

import java.util.Set;

/** 逻辑合并父单受理命令。 */
public record WmsLogicalMergeStartCommand(
    String commandKey,
    Long targetScopeVersionId,
    Set<Long> sourceScopeVersionIds,
    Set<Long> compatibleSourceScopeVersionIds,
    Integer batchSize,
    String reason
) {
    public WmsLogicalMergeStartCommand {
        sourceScopeVersionIds = sourceScopeVersionIds == null
            ? null : Set.copyOf(sourceScopeVersionIds);
        compatibleSourceScopeVersionIds = compatibleSourceScopeVersionIds == null
            ? null : Set.copyOf(compatibleSourceScopeVersionIds);
    }
}
