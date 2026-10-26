/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 应用、版本与公开下载查询服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.system.apk.domain.vo.ApkApplicationVO;
import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * APK 应用、版本与公开下载查询服务。
 *
 * @author YONGJIE.PENG
 */
public interface IApkReleaseQueryService {

    /**
     * 分页查询管理员应用汇总。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @param pageQuery   分页参数
     * @return 应用汇总分页
     */
    PageResult<ApkApplicationVO> queryApplications(String packageName, String appName, PageQuery pageQuery);

    /**
     * 分页查询管理员版本历史。
     *
     * @param packageName 安装包名称
     * @param pageQuery   分页参数
     * @return 版本历史分页
     */
    PageResult<ApkReleaseVO> queryHistory(String packageName, PageQuery pageQuery);

    /**
     * 查询公开的最新已发布版本。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @return 最新已发布版本
     */
    ApkReleaseVO queryLatestPublished(String packageName, String appName);

    /**
     * 分页查询公开版本历史。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @param pageQuery   分页参数
     * @return 已发布版本分页
     */
    PageResult<ApkReleaseVO> queryPublishedHistory(String packageName, String appName, PageQuery pageQuery);

    /**
     * 将公开 APK 流式写入响应。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @param versionCode 版本号
     * @param versionName 版本名称
     * @param response    HTTP 响应
     * @throws IOException 读取或写出对象失败
     */
    void downloadPublished(String packageName, String appName, String versionCode, String versionName,
                           HttpServletResponse response) throws IOException;
}
