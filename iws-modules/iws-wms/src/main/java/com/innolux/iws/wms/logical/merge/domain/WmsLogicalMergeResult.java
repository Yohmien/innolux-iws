/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 返回逻辑归属合并批次、冲突保留和已提交历史事实；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.domain;

import com.innolux.iws.wms.logical.merge.WmsLogicalMergePlanner;

import java.util.List;

/**
 * 已提交逻辑归属合并结果。
 *
 * @param effectKey 批次领域效果键
 * @param inputDigest 冻结输入摘要
 * @param targetScopeVersionId 目标作用域版本 ID
 * @param batches 合并计划分批
 * @param migratedAssignments 本批已迁移归属
 * @param migratedCount 已迁移数量
 * @param alreadyTargetCount 已位于目标作用域数量
 * @param conflictRetainedCount 保留原归属的冲突数量
 * @param replayed 是否由既有批次事实幂等回放
 */
public record WmsLogicalMergeResult(
    String effectKey,
    String inputDigest,
    long targetScopeVersionId,
    List<WmsLogicalMergePlanner.Batch> batches,
    List<MigratedAssignment> migratedAssignments,
    int migratedCount,
    int alreadyTargetCount,
    int conflictRetainedCount,
    boolean replayed
) {

    public WmsLogicalMergeResult {
        batches = List.copyOf(batches);
        migratedAssignments = List.copyOf(migratedAssignments);
    }

    /**
     * 单物料已迁移归属事实。
     *
     * @param historyId 归属历史 ID
     * @param inventoryId 稳定库存 ID
     * @param assignmentId 逻辑归属 ID
     * @param fromScopeVersionId 变更前作用域版本 ID
     * @param toScopeVersionId 变更后作用域版本 ID
     * @param previousRevision 变更前归属修订号
     * @param currentRevision 变更后归属修订号
     */
    public record MigratedAssignment(
        long historyId,
        long inventoryId,
        long assignmentId,
        long fromScopeVersionId,
        long toScopeVersionId,
        int previousRevision,
        int currentRevision
    ) {
    }
}
