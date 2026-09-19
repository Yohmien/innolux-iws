/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.innolux.iws.common.core.utils.StringUtils;

/**
 * 用户类型
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum UserType {

    /**
     * 后台系统用户
     */
    SYS_USER("sys_user"),

    /**
     * 移动客户端用户
     */
    APP_USER("app_user");

    /**
     * 用户类型标识（用于 token、权限识别等）
     */
    private final String userType;

    /**
     * 根据字符串内容匹配用户类型。
     *
     * @param str 待匹配字符串
     * @return 用户类型
     */
    public static UserType getUserType(String str) {
        for (UserType value : values()) {
            if (StringUtils.contains(str, value.getUserType())) {
                return value;
            }
        }
        throw new RuntimeException("'UserType' not found By " + str);
    }
}
