/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 冻结释放任务所属的父业务、分配单元和计划实例；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.domain;

/**
 * 分配释放任务身份。
 *
 * @param businessType 父业务类型
 * @param businessName 父业务类型名称快照
 * @param businessId 父业务 ID
 * @param businessNo 父业务号
 * @param businessLineId 父业务明细 ID
 * @param allocationUnitId 不可拆分配单元 ID
 * @param roundNo 分配轮次
 * @param taskKey 释放任务稳定键
 * @param lockTaskKey 原分配锁任务稳定键
 * @param planInstanceId 释放计划实例 ID
 */
public record WmsAllocationReleaseIdentity(
    String businessType,
    String businessName,
    long businessId,
    String businessNo,
    Long businessLineId,
    long allocationUnitId,
    int roundNo,
    String taskKey,
    String lockTaskKey,
    long planInstanceId
) {
}
