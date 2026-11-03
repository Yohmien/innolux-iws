/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务
 * @Version      : V1.0
 * @Description  : 分配锁的父业务、不可拆单元与计划身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation.domain;

/** 一次分配锁必须冻结的父业务和计划身份。 */
public record WmsAllocationBusinessContext(
    String businessType,
    String businessName,
    long businessId,
    String businessNo,
    Long businessLineId,
    long allocationUnitId,
    int roundNo,
    String taskKey,
    long planInstanceId
) {
}
