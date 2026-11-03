/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P6 逻辑归属合并
 * @Version      : V1.0
 * @Description  : 冻结来源作用域最终退役的稳定效果和操作身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.logical.merge.domain;

import java.time.LocalDateTime;
import java.util.Set;

/** 来源作用域最终退役命令。 */
public record WmsLogicalMergeRetireCommand(
    String effectKey,
    Set<Long> sourceScopeVersionIds,
    String taskKey,
    Long planInstanceId,
    LocalDateTime occurredAt,
    String reason,
    Long operatorId,
    String operatorName,
    Long operatorDepartmentId,
    String operatorDepartmentName
) {
    public WmsLogicalMergeRetireCommand {
        sourceScopeVersionIds = sourceScopeVersionIds == null
            ? null : Set.copyOf(sourceScopeVersionIds);
    }
}
