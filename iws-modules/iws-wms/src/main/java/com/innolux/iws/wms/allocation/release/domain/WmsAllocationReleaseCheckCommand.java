/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 冻结无外部效果证据及待释放分配锁集合；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.domain;

import java.util.List;

/**
 * 释放证据检查命令。
 *
 * @param identity 释放任务身份
 * @param evidenceType 证据类型：NO_EFFECT 或 RECONCILED_NO_EFFECT
 * @param evidenceRef 脱敏证据引用
 * @param allocationLockIds 待释放分配锁 ID
 */
public record WmsAllocationReleaseCheckCommand(
    WmsAllocationReleaseIdentity identity,
    String evidenceType,
    String evidenceRef,
    List<Long> allocationLockIds
) {
    public WmsAllocationReleaseCheckCommand {
        allocationLockIds = allocationLockIds == null ? null : List.copyOf(allocationLockIds);
    }
}
