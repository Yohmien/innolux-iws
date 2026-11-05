/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 应用分组分页内部行；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.domain.vo;

import lombok.Data;

/**
 * APK 应用分组分页内部行。
 *
 * @author YONGJIE.PENG
 */
@Data
public class ApkApplicationRow {

    /**
     * 安装包名称
     */
    private String packageName;

    /**
     * 最新选中版本的应用名称
     */
    private String appName;

    /**
     * 同包全部历史版本数量
     */
    private Long versionCount;

    /**
     * 最新选中版本主键
     */
    private Long latestReleaseId;
}
