/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 指定释放效果键和分配锁集合供终态核验；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.domain;

import java.util.List;

/**
 * 分配释放核验命令。
 *
 * @param effectKey 释放领域效果键
 * @param evidenceHash 释放时证据摘要
 * @param identity 释放任务身份
 * @param allocationLockIds 分配锁 ID
 */
public record WmsAllocationReleaseVerifyCommand(
    String effectKey,
    String evidenceHash,
    WmsAllocationReleaseIdentity identity,
    List<Long> allocationLockIds
) {
    public WmsAllocationReleaseVerifyCommand {
        allocationLockIds = allocationLockIds == null ? null : List.copyOf(allocationLockIds);
    }
}
