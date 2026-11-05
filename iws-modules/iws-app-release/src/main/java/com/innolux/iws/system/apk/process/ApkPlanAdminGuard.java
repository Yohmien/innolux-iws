/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布计划管理员操作门禁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

/**
 * 显式初始化或核验 APK 发布计划前的管理员权限门禁。
 */
@FunctionalInterface
public interface ApkPlanAdminGuard {

    /**
     * 校验当前调用者是否具有 APK 计划管理权限；失败时抛出运行时异常。
     */
    void check();
}
