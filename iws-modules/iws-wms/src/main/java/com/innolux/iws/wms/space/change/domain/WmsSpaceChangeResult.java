/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 返回空间变更当前持久状态与版本证据；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.domain;

/** 空间变更结果。 */
public record WmsSpaceChangeResult(
    long changeOrderId,
    String changeType,
    String objectType,
    long objectId,
    String status,
    String configDigest,
    Long locationVersionId,
    Integer locationEffectiveRevision,
    Integer objectRevision
) {
}
