/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 发布版本实体；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.innolux.iws.common.mybatis.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * APK 发布版本。
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_apk_release")
public class ApkRelease extends BaseEntity {

    /**
     * 发布版本主键
     */
    @TableId(value = "release_id", type = IdType.ASSIGN_ID)
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
    private Long versionCode;

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
    private Long ossId;

    /**
     * 发布状态
     */
    private String status;

    /**
     * 计划实例主键
     */
    private Long processInstanceId;

    /**
     * 备注
     */
    private String remark;
}
