/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : 使用 Sa-Token 校验 APK 计划管理员权限；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * 使用当前登录会话校验 APK 计划管理权限。
 */
@Component
public class SaTokenApkPlanAdminGuard implements ApkPlanAdminGuard {

    /**
     * 要求登录并具有 {@code system:apk:publish} 权限。
     */
    @Override
    public void check() {
        if (!StpUtil.isLogin()) {
            throw new ServiceException("未登录，不能初始化 APK 发布计划");
        }
        try {
            StpUtil.checkPermission(ApkProcessContract.PLAN_ADMIN_PERMISSION);
        } catch (NotPermissionException ex) {
            throw new ServiceException("需要 APK 发布计划管理权限: "
                + ApkProcessContract.PLAN_ADMIN_PERMISSION);
        }
    }
}
