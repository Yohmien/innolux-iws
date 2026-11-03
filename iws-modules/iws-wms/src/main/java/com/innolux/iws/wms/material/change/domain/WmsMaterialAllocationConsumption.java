/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料事实
 * @Version      : V1.0
 * @Description  : 描述物料事实需要同事务消费的原分配锁代次；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.material.change.domain;

import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectExpectation;
import com.innolux.iws.wms.allocation.effect.domain.WmsAllocationEffectIdentity;

import java.util.List;

/**
 * 物料事实的分配锁消费输入。
 *
 * @param identity 原分配锁业务身份
 * @param expectations 逐锁代次期望
 * @param evidenceRef 脱敏证据引用
 */
public record WmsMaterialAllocationConsumption(
    WmsAllocationEffectIdentity identity,
    List<WmsAllocationEffectExpectation> expectations,
    String evidenceRef
) {
    public WmsMaterialAllocationConsumption {
        expectations = expectations == null ? null : List.copyOf(expectations);
    }
}
