/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P2 空间生命周期
 * @Version      : V1.0
 * @Description  : 固化一次空间变更校验得到的对象身份和配置摘要；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.space.change.domain;

/** 空间变更校验结果。 */
public record WmsSpaceChangeValidation(
    long changeOrderId,
    String changeType,
    String objectType,
    long objectId,
    Integer expectedRevision,
    Integer currentRevision,
    long companyId,
    Long warehouseId,
    Long zoneId,
    String configJson,
    String configDigest,
    String status
) {
}
