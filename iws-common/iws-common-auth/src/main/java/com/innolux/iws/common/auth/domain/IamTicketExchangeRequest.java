/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 目标应用核销中心票据的请求；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 一次性票据核销请求。
 *
 * @param applicationId 目标应用标识
 * @param frontendId 发起登录的受信前端
 * @param ticket   中心签发的一次性票据
 * @param state    客户端原始随机状态
 * @param commitId 目标应用本次准备/提交的服务端幂等标识
 */
public record IamTicketExchangeRequest(
    String applicationId,
    String frontendId,
    String ticket,
    String state,
    String commitId
) {
    /**
     * 兼容只读检查请求；提交接口必须显式提供 commitId。
     */
    public IamTicketExchangeRequest(
        String applicationId,
        String frontendId,
        String ticket,
        String state
    ) {
        this(applicationId, frontendId, ticket, state, null);
    }
}
