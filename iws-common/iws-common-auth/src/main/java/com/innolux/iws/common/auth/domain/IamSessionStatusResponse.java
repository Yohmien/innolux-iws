/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 根会话有效性查询结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

/**
 * 根会话状态。
 *
 * @param active      是否有效
 * @param issuer      权威签发方
 * @param userId      原系统用户主键
 * @param username    当前原系统账号名
 * @param expiresAt   过期时间戳
 * @param reason      失效原因
 */
public record IamSessionStatusResponse(
    boolean active,
    String issuer,
    Long userId,
    String username,
    long expiresAt,
    String reason
) {

    /**
     * 兼容不需要账号名的内部状态构造；下游主体绑定必须读取包含账号名的状态。
     *
     * @param active    是否有效
     * @param issuer    权威签发方
     * @param userId    原系统用户主键
     * @param expiresAt 过期时间戳
     * @param reason    失效原因
     */
    public IamSessionStatusResponse(
        boolean active,
        String issuer,
        Long userId,
        long expiresAt,
        String reason
    ) {
        this(active, issuer, userId, null, expiresAt, reason);
    }
}
