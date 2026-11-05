/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 按票据和内部提交标识缓存的幂等结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 目标应用同一次服务端提交的幂等结果。
 *
 * @param state 受绑定客户端状态
 * @param commitId 目标应用准备/提交标识
 * @param response 已完成身份结果
 */
public record IamTicketCommitResult(
    String state,
    String commitId,
    IamTicketExchangeResponse response
) {
}
