/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配效果代次
 * @Version      : V1.0
 * @Description  : 冻结原分配锁所属父业务、分配单元和计划实例；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.effect.domain;

/**
 * 分配锁外部效果任务身份。
 *
 * @param businessType 父业务类型
 * @param businessName 父业务类型名称快照
 * @param businessId 父业务 ID
 * @param businessNo 父业务号
 * @param businessLineId 父业务明细 ID
 * @param allocationUnitId 不可拆分配单元 ID
 * @param roundNo 分配轮次
 * @param taskKey 原分配锁任务键
 * @param planInstanceId 原分配锁计划实例 ID
 */
public record WmsAllocationEffectIdentity(
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
