/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 返回已释放分配锁、通道锁和检查点；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.domain;

import java.util.List;

/**
 * 分配锁释放结果。
 *
 * @param effectKey 释放领域效果键
 * @param evidenceHash 证据摘要
 * @param evidenceRevision 证据修订号
 * @param allocationLockIds 已释放分配锁 ID
 * @param releaseIds 追加释放流水 ID
 * @param releasedChannelLockIds 已终结通道锁 ID
 * @param checkpointJson 计划 checkpoint
 */
public record WmsAllocationReleaseResult(
    String effectKey,
    String evidenceHash,
    int evidenceRevision,
    List<Long> allocationLockIds,
    List<Long> releaseIds,
    List<Long> releasedChannelLockIds,
    String checkpointJson
) {
    public WmsAllocationReleaseResult {
        allocationLockIds = List.copyOf(allocationLockIds);
        releaseIds = List.copyOf(releaseIds);
        releasedChannelLockIds = List.copyOf(releasedChannelLockIds);
    }
}
