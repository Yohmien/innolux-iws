/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 发布状态；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.enums;

/**
 * APK 发布状态。
 *
 * @author YONGJIE.PENG
 */
public enum ApkReleaseStatus {

    /**
     * 已上传，等待发布流程
     */
    UPLOADED,

    /**
     * 已发布，可匿名查询和下载
     */
    PUBLISHED;

    /**
     * 判断是否为已发布状态。
     *
     * @param status 状态值
     * @return true=已发布
     */
    public static boolean isPublished(String status) {
        return PUBLISHED.name().equals(status);
    }
}
