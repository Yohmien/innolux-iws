/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 应用汇总视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * APK 应用汇总视图。
 *
 * @author YONGJIE.PENG
 */
@Data
public class ApkApplicationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 安装包名称
     */
    private String packageName;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 版本数量
     */
    private Long versionCount;

    /**
     * 最新已发布版本；没有已发布版本时为最新上传版本
     */
    private ApkReleaseVO latestVersion;
}
