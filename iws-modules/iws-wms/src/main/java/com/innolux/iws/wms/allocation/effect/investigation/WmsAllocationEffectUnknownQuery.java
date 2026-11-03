/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果查证
 * @Version      : V1.0
 * @Description  : 查询待外部查证的UNKNOWN分配效果；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.investigation;

/**
 * UNKNOWN 分配效果查询条件。
 *
 * @param businessType 可空父业务类型
 * @param businessId 可空父业务 ID
 * @param taskKey 可空分配锁任务键
 * @param limit 返回上限
 */
public record WmsAllocationEffectUnknownQuery(
    String businessType,
    Long businessId,
    String taskKey,
    int limit
) {

    public WmsAllocationEffectUnknownQuery {
        businessType = blankToNull(businessType);
        taskKey = blankToNull(taskKey);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
