/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 推送长连接所属账号和本地 Token；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.push.core;

/**
 * 可供认证上下文复核的推送连接标识。
 *
 * @param userId 本地登录账号主键
 * @param token  本地登录 Token
 */
public record PushSessionIdentity(Long userId, String token) {
}
