/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 上传、重试发布与计划初始化服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service;

import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * APK 上传、重试发布与计划初始化服务。
 *
 * @author YONGJIE.PENG
 */
public interface IApkReleaseCommandService {

    /**
     * 解析、上传并登记 APK 版本。
     *
     * @param file   APK 文件
     * @param remark 备注
     * @return 登记后的版本
     */
    ApkReleaseVO upload(MultipartFile file, String remark);

    /**
     * 解析、上传并登记 APK 版本，同时设置强制更新策略。
     *
     * @param file        APK 文件
     * @param remark      备注
     * @param forceUpdate 是否强制更新，空值按 true 处理
     * @return 登记后的版本
     */
    ApkReleaseVO upload(MultipartFile file, String remark, Boolean forceUpdate);

    /**
     * 修改已有版本的强制更新策略。
     *
     * @param releaseId   发布版本 ID
     * @param forceUpdate 是否强制更新
     */
    void updateForceUpdate(long releaseId, boolean forceUpdate);

    /**
     * 重试已上传版本的发布。
     *
     * @param releaseId 发布版本 ID
     * @return 发布后的最新版本
     */
    ApkReleaseVO retryPublish(long releaseId);

    /**
     * 显式初始化或核验 APK 发布计划。
     */
    void initializePlan();
}
