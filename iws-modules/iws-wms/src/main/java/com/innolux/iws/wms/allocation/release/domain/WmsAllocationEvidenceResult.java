/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 分配释放
 * @Version      : V1.0
 * @Description  : 返回可写入计划 checkpoint 的释放证据快照；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.release.domain;

import java.util.List;

/**
 * 释放证据检查结果。
 *
 * @param evidenceHash 规范化证据摘要
 * @param evidenceRevision 证据修订号
 * @param channelLockIds 关联通道锁 ID
 * @param expectations 分配锁当前值
 * @param checkpointJson 计划 checkpoint
 */
public record WmsAllocationEvidenceResult(
    String evidenceHash,
    int evidenceRevision,
    List<Long> channelLockIds,
    List<WmsAllocationReleaseLockExpectation> expectations,
    String checkpointJson
) {
    public WmsAllocationEvidenceResult {
        channelLockIds = List.copyOf(channelLockIds);
        expectations = List.copyOf(expectations);
    }
}
