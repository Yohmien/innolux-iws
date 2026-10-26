/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 管理员查询、上传、发布重试与计划初始化入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.system.apk.domain.vo.ApkApplicationVO;
import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import com.innolux.iws.system.apk.service.IApkReleaseCommandService;
import com.innolux.iws.system.apk.service.IApkReleaseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * APK 管理员入口。
 *
 * @author YONGJIE.PENG
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/apk")
public class ApkReleaseController {

    private final IApkReleaseQueryService queryService;

    private final IApkReleaseCommandService commandService;

    /**
     * 分页查询安装包应用汇总。
     *
     * @param packageName 安装包名称
     * @param appName     应用名称
     * @param pageQuery   分页参数
     * @return 应用汇总分页
     */
    @SaCheckPermission("system:apk:list")
    @GetMapping("/list")
    public R<PageResult<ApkApplicationVO>> list(
        @RequestParam(value = "packageName", required = false) String packageName,
        @RequestParam(value = "appName", required = false) String appName,
        PageQuery pageQuery) {
        return R.ok(queryService.queryApplications(packageName, appName, pageQuery));
    }

    /**
     * 分页查询指定安装包的版本历史。
     *
     * @param packageName 安装包名称
     * @param pageQuery   分页参数
     * @return 版本历史分页
     */
    @SaCheckPermission("system:apk:list")
    @GetMapping("/history")
    public R<PageResult<ApkReleaseVO>> history(
        @RequestParam(value = "packageName", required = false) String packageName,
        PageQuery pageQuery) {
        return R.ok(queryService.queryHistory(packageName, pageQuery));
    }

    /**
     * 上传 APK 并登记待发布版本。
     *
     * @param file    APK 文件
     * @param remark  备注
     * @return 登记后的版本
     */
    @SaCheckPermission("system:apk:upload")
    @Log(title = "APK 上传", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<ApkReleaseVO> upload(@RequestPart("file") MultipartFile file,
                                  @RequestParam(value = "remark", required = false) String remark) {
        return R.ok(commandService.upload(file, remark));
    }

    /**
     * 重试 APK 发布。
     *
     * @param releaseId 发布版本 ID
     * @return 发布后的最新版本
     */
    @SaCheckPermission("system:apk:publish")
    @Log(title = "APK 发布重试", businessType = BusinessType.UPDATE)
    @PostMapping("/{releaseId}/publish")
    public R<ApkReleaseVO> publish(@PathVariable("releaseId") long releaseId) {
        return R.ok(commandService.retryPublish(releaseId));
    }

    /**
     * 初始化或核验 APK 发布计划。
     *
     * @return 操作结果
     */
    @SaCheckPermission("system:apk:publish")
    @Log(title = "APK 发布计划初始化", businessType = BusinessType.UPDATE)
    @PostMapping("/initialize")
    public R<Void> initialize() {
        commandService.initializePlan();
        return R.ok();
    }
}
