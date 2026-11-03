/*
 * @Date         : 2026-10-30
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道配置
 * @Version      : V1.0
 * @Description  : 返回通道定义变更后的稳定字段和修订号；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.domain;

/**
 * 访问通道定义变更结果。
 *
 * @param channelId 通道 ID
 * @param channelCode 当前稳定编码
 * @param accessMode 当前访问模式
 * @param status 当前状态
 * @param revision 当前修订号
 */
public record WmsChannelDefinitionChangeResult(
    long channelId,
    String channelCode,
    String accessMode,
    String status,
    int revision
) {
}
