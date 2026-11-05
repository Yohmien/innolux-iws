/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 中心签发的单次应用跳转票据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 应用跳转授权结果。
 *
 * @param ticket      一次性票据
 * @param redirectUrl 目标应用回调地址
 * @param expiresAt   票据过期时间戳
 */
public record IamAuthorizationResponse(String ticket, String redirectUrl, long expiresAt) {
}
