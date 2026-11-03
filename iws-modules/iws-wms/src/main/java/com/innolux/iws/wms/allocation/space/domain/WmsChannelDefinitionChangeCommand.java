/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道配置
 * @Version      : V1.0
 * @Description  : 冻结通道编码、访问模式和状态变更的期望修订号；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

import java.time.LocalDateTime;

/**
 * 访问通道定义变更命令。
 *
 * @param channelId 通道 ID
 * @param expectedRevision 期望通道修订号
 * @param channelCode 目标稳定编码
 * @param accessMode 目标访问模式
 * @param status 目标状态
 * @param occurredAt 操作发生时间
 * @param actorId 操作者 ID
 * @param departmentId 操作部门 ID
 */
public record WmsChannelDefinitionChangeCommand(
    long channelId,
    int expectedRevision,
    String channelCode,
    String accessMode,
    String status,
    LocalDateTime occurredAt,
    Long actorId,
    Long departmentId
) {
}
