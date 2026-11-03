/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 阻挡边
 * @Version      : V1.0
 * @Description  : 冻结删除储位阻挡边的身份和期望修订号；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

import java.time.LocalDateTime;

/**
 * 删除储位阻挡边命令。
 *
 * @param accessEdgeId 阻挡边 ID
 * @param expectedRevision 期望阻挡边修订号
 * @param occurredAt 操作发生时间
 * @param actorId 操作者 ID
 * @param departmentId 操作部门 ID
 */
public record WmsStorageAccessEdgeRemoveCommand(
    long accessEdgeId,
    int expectedRevision,
    LocalDateTime occurredAt,
    Long actorId,
    Long departmentId
) {
}
