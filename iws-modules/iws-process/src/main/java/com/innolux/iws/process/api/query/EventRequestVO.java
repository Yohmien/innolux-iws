/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 单事件请求方向明细只读快照；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

/**
 * 单事件请求方向明细只读快照。
 *
 * <p>正文只返回数据库已保存的脱敏摘要，通过独立请求权限懒加载；总览和链视图不携带。</p>
 *
 * @param requestId 请求 ID
 * @param direction 方向
 * @param purpose 用途
 * @param method HTTP 方法
 * @param url 接口地址
 * @param statusCode HTTP 状态
 * @param requestBody 脱敏请求摘要
 * @param responseBody 脱敏响应摘要
 * @param redacted 是否已脱敏
 * @param effectResult 效果结果
 * @param sent 是否真实发送
 * @param correlationId 调用链标识
 * @param observedAt 观察时间
 * @param instanceId 实例 ID
 * @param eventId 事件 ID
 * @param callContractKey 调用合同键
 */
public record EventRequestVO(String requestId, String direction, String purpose, String method, String url,
                             Integer statusCode, String requestBody, String responseBody, Boolean redacted,
                             String effectResult, Boolean sent, String correlationId, String observedAt,
                             String instanceId, String eventId, String callContractKey) {
}
