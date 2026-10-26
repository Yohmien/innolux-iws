/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 发布版本视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.domain.vo;

import com.innolux.iws.system.apk.domain.ApkRelease;
import lombok.Data;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * APK 发布版本视图。
 *
 * @author YONGJIE.PENG
 */
@Data
public class ApkReleaseVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发布版本主键
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long releaseId;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 安装包名称
     */
    private String packageName;

    /**
     * 版本名称
     */
    private String versionName;

    /**
     * 版本号
     */
    private String versionCode;

    /**
     * APK 文件 SHA-256
     */
    private String sha256;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * OSS 文件主键
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ossId;

    /**
     * 发布状态
     */
    private String status;

    /**
     * 是否强制更新
     */
    private Boolean forceUpdate;

    /**
     * 计划实例主键
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long processInstanceId;

    /**
     * 下载次数
     */
    private Long downloadCount;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 匿名下载地址
     */
    private String downloadUrl;

    /**
     * 转换发布版本视图。
     *
     * @param release 发布版本实体
     * @return 发布版本视图
     */
    public static ApkReleaseVO from(ApkRelease release) {
        ApkReleaseVO vo = new ApkReleaseVO();
        vo.setReleaseId(release.getReleaseId());
        vo.setAppName(release.getAppName());
        vo.setPackageName(release.getPackageName());
        vo.setVersionName(release.getVersionName());
        vo.setVersionCode(release.getVersionCode() == null ? null : release.getVersionCode().toString());
        vo.setSha256(release.getSha256());
        vo.setFileSize(release.getFileSize());
        vo.setOssId(release.getOssId());
        vo.setStatus(release.getStatus());
        vo.setForceUpdate(release.getForceUpdate());
        vo.setProcessInstanceId(release.getProcessInstanceId());
        vo.setDownloadCount(release.getDownloadCount());
        vo.setRemark(release.getRemark());
        vo.setCreateTime(release.getCreateTime());
        vo.setUpdateTime(release.getUpdateTime());
        vo.setDownloadUrl(downloadUrl(release.getPackageName(), release.getVersionCode()));
        return vo;
    }

    /**
     * 构建不依赖后端主机地址的下载地址。
     *
     * @param packageName 安装包名称
     * @param versionCode 版本号
     * @return 相对下载地址
     */
    private static String downloadUrl(String packageName, Long versionCode) {
        if (packageName == null || versionCode == null) {
            return null;
        }
        return "/app/apk/download?packageName=" + UriUtils.encodeQueryParam(packageName, StandardCharsets.UTF_8)
            + "&versionCode=" + versionCode;
    }
}
