/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 匿名版本查询与流式下载入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import com.innolux.iws.system.apk.service.IApkReleaseQueryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * APK 匿名查询与下载入口。
 *
 * @author YONGJIE.PENG
 */
@SaIgnore
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/apk")
public class ApkPublicController {

    private final IApkReleaseQueryService queryService;

    /**
     * 查询最新已发布版本。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @return 最新已发布版本
     */
    @GetMapping("/latest")
    public R<ApkReleaseVO> latest(
        @RequestParam(value = "packageName", required = false) String packageName,
        @RequestParam(value = "appName", required = false) String appName) {
        return R.ok(queryService.queryLatestPublished(packageName, appName));
    }

    /**
     * 分页查询已发布版本历史。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @param pageQuery   分页参数
     * @return 已发布版本分页
     */
    @GetMapping("/history")
    public R<PageResult<ApkReleaseVO>> history(
        @RequestParam(value = "packageName", required = false) String packageName,
        @RequestParam(value = "appName", required = false) String appName,
        PageQuery pageQuery) {
        return R.ok(queryService.queryPublishedHistory(packageName, appName, pageQuery));
    }

    /**
     * 下载已发布 APK。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @param versionCode 版本号
     * @param versionName 版本名称
     * @param response    HTTP 响应
     * @throws IOException 读取或写出文件失败
     */
    @GetMapping("/download")
    public void download(
        @RequestParam(value = "packageName", required = false) String packageName,
        @RequestParam(value = "appName", required = false) String appName,
        @RequestParam(value = "versionCode", required = false) String versionCode,
        @RequestParam(value = "versionName", required = false) String versionName,
        HttpServletResponse response) throws IOException {
        queryService.downloadPublished(packageName, appName, versionCode, versionName, response);
    }
}
