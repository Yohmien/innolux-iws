/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.2
 * @Description  : 统一下游会话共享的根会话状态；
 * @ModifyRecord : V1.0 新建；V1.1 增加账号凭据指纹，供下游会话状态统一复核；
 *                  V1.2 增加完整授权指纹，覆盖角色数据范围与部门范围；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 中心根会话。
 *
 * @param issuer  权威签发方
 * @param rootSid 根会话标识
 * @param userId  原系统用户主键
 * @param username 原系统账号
 * @param credentialFingerprint 账号密码哈希指纹
 * @param authorizationFingerprint 菜单、角色及数据范围共享指纹；应用接入资格单独校验
 * @param createdAt 创建时间戳
 * @param expiresAt 过期时间戳
 */
public record IamRootSession(
    String issuer,
    String rootSid,
    Long userId,
    String username,
    String credentialFingerprint,
    String authorizationFingerprint,
    long createdAt,
    long expiresAt
) {
}
