/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 统一门户中的应用与接入授权状态；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

import java.util.List;

/**
 * 统一门户应用条目。
 *
 * @param applicationId 目标应用标识
 * @param applicationType 应用类型
 * @param name        应用名称
 * @param description 应用说明
 * @param frontends   当前应用允许的受信前端入口
 * @param permission  接入权限码，空表示所有有效账号可接入
 * @param granted     当前用户是否具备接入权限
 */
public record IamPortalApplication(
    String applicationId,
    String applicationType,
    String name,
    String description,
    List<FrontendLaunch> frontends,
    String permission,
    boolean granted
) {
    /**
     * 受信前端入口投影。
     *
     * @param frontendId 前端标识
     * @param terminal   终端
     * @param launchUri  入口地址
     */
    public record FrontendLaunch(String frontendId, String terminal, String launchUri) {
    }
}
