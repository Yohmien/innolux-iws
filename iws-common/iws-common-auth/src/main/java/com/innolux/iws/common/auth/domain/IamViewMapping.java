/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 应用、终端和原菜单到受控前端界面的映射；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

import java.util.List;

/**
 * 菜单界面映射。
 *
 * @param applicationId        目标应用标识
 * @param terminal             终端类型
 * @param menuId               原菜单主键
 * @param viewKey              构建时登记的前端页面键
 * @param requiredCapabilities 页面需要的受控能力
 */
public record IamViewMapping(
    String applicationId,
    String terminal,
    Long menuId,
    String viewKey,
    List<String> requiredCapabilities
) {
}
