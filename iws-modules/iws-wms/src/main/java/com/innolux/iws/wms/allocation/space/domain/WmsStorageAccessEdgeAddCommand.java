/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 阻挡边
 * @Version      : V1.0
 * @Description  : 冻结新增储位阻挡边的稳定储位身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

import java.time.LocalDateTime;

/**
 * 新增储位阻挡边命令。
 *
 * @param blockerStorageId 阻挡储位 ID
 * @param blockedStorageId 被阻挡储位 ID
 * @param occurredAt 操作发生时间
 * @param actorId 操作者 ID
 * @param departmentId 操作部门 ID
 */
public record WmsStorageAccessEdgeAddCommand(
    long blockerStorageId,
    long blockedStorageId,
    LocalDateTime occurredAt,
    Long actorId,
    Long departmentId
) {
}
