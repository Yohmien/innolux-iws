/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 为推送握手提供目标应用自己的当前身份校验接缝；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.push.service;

/**
 * 推送连接当前身份授权器。
 */
@FunctionalInterface
public interface PushConnectionAuthorizer {

    /**
     * 校验票据恢复出的本地 Token 当前仍具备推送访问资格。
     *
     * @param token 本地登录 Token
     * @return 是否允许建立或继续使用推送连接
     */
    boolean authorize(String token);
}
