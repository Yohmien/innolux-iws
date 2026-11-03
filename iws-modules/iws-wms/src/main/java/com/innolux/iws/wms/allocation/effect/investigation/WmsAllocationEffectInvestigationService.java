/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果查证
 * @Version      : V1.0
 * @Description  : 只读返回待外部查证的UNKNOWN分配效果；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.investigation;

import java.util.List;

/** UNKNOWN 分配效果查证服务。 */
public interface WmsAllocationEffectInvestigationService {

    /**
     * 查询仍处于活动 UNKNOWN 状态的分配锁及最近证据。
     *
     * @param query 查询条件
     * @return 待外部查证效果
     */
    List<WmsAllocationEffectUnknownView> findUnknown(
        WmsAllocationEffectUnknownQuery query
    );
}
