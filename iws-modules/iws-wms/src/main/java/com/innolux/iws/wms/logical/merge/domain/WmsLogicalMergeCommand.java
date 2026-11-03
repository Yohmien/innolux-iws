/*
 * @Date         : 2026-11-01
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 冻结目标作用域、来源范围、物料归属快照和批次幂等身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.domain;

import com.innolux.iws.wms.logical.merge.WmsLogicalMergePlanner;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 逻辑归属合并批次命令。调用者必须已开启事务。
 *
 * @param effectKey 批次领域效果键
 * @param target 冻结的目标生效作用域
 * @param sourceScopeVersionIds 固定来源作用域版本集合
 * @param compatibleSourceScopeVersionIds 显式确认规则兼容的来源版本
 * @param batchSize 每批最大物料数
 * @param expectedAssignments 调用者读取的物料归属快照，锁后逐项 CAS
 * @param taskKey 可选 WMS 任务键
 * @param planInstanceId 可选计划实例 ID
 * @param occurredAt 业务发生时间
 * @param reason 业务原因
 * @param operatorId 操作者 ID 快照
 * @param operatorName 操作者名称快照
 * @param operatorDepartmentId 操作者部门 ID 快照
 * @param operatorDepartmentName 操作者部门名称快照
 */
public record WmsLogicalMergeCommand(
    String effectKey,
    WmsLogicalMergePlanner.TargetScope target,
    Set<Long> sourceScopeVersionIds,
    Set<Long> compatibleSourceScopeVersionIds,
    int batchSize,
    List<WmsLogicalMergePlanner.Assignment> expectedAssignments,
    String taskKey,
    Long planInstanceId,
    LocalDateTime occurredAt,
    String reason,
    Long operatorId,
    String operatorName,
    Long operatorDepartmentId,
    String operatorDepartmentName
) {

    public WmsLogicalMergeCommand {
        sourceScopeVersionIds = sourceScopeVersionIds == null ? null : Set.copyOf(sourceScopeVersionIds);
        compatibleSourceScopeVersionIds = compatibleSourceScopeVersionIds == null
            ? null : Set.copyOf(compatibleSourceScopeVersionIds);
        expectedAssignments = expectedAssignments == null ? null : List.copyOf(expectedAssignments);
    }

    /**
     * 转换为纯合并计划输入。
     *
     * @return 计划内核命令
     */
    public WmsLogicalMergePlanner.Command planCommand() {
        return new WmsLogicalMergePlanner.Command(
            target,
            sourceScopeVersionIds,
            compatibleSourceScopeVersionIds,
            batchSize,
            expectedAssignments
        );
    }
}
