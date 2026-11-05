/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 当前后端与终端可用的菜单界面项；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

import java.util.List;

/**
 * 当前账号可用的菜单界面。
 *
 * @param menuId               原菜单主键
 * @param parentId             父菜单主键
 * @param viewKey              受控页面键
 * @param requiredCapabilities 页面所需能力
 */
public record IamViewContextItem(
    Long menuId,
    Long parentId,
    String viewKey,
    List<String> requiredCapabilities
) {
}
