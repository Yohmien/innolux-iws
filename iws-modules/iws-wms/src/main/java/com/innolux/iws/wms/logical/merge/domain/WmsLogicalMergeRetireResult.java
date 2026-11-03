/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 返回来源作用域版本和逻辑归属的退役事实；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.domain;

import java.util.List;

/** 来源作用域退役结果。 */
public record WmsLogicalMergeRetireResult(
    String effectKey,
    String inputDigest,
    List<Long> retiredScopeVersionIds,
    List<Long> retiredScopeIds,
    boolean replayed
) {
    public WmsLogicalMergeRetireResult {
        retiredScopeVersionIds = List.copyOf(retiredScopeVersionIds);
        retiredScopeIds = List.copyOf(retiredScopeIds);
    }
}
