/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.controller.system;


import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.validate.QueryGroup;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.web.core.BaseController;
import com.innolux.iws.system.domain.SysOssExt;
import com.innolux.iws.system.domain.bo.SysOssBo;
import com.innolux.iws.system.domain.vo.SysOssVo;
import com.innolux.iws.system.service.ISysOssService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 文件上传 控制层
 *
 * @author YONGJIE.PENG
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/resource/oss")
public class SysOssController extends BaseController {

    private final ISysOssService ossService;

    /**
     * 分页查询 OSS 对象存储列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return OSS 分页结果
     */
    @SaCheckPermission("system:oss:list")
    @GetMapping("/list")
    public R<PageResult<SysOssVo>> list(@Validated(QueryGroup.class) SysOssBo bo, PageQuery pageQuery) {
        return R.ok(ossService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询OSS对象基于id串
     *
     * @param ossIds OSS对象ID串
     * @return OSS 对象列表
     */
    @SaCheckPermission("system:oss:query")
    @GetMapping("/listByIds/{ossIds}")
    public R<List<SysOssVo>> listByIds(@NotEmpty(message = "主键不能为空")
                                       @PathVariable Long[] ossIds) {
        List<SysOssVo> list = ossService.listByIds(Arrays.asList(ossIds));
        return R.ok(list);
    }

    /**
     * 上传OSS对象存储
     *
     * @param file 文件
     * @return 上传结果
     */
    @SaCheckPermission("system:oss:upload")
    @Log(title = "OSS对象存储", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<SysOssUploadVo> upload(@RequestPart("file") MultipartFile file, @RequestParam(value = "ossExt", required = false) String ossExtJson) {
        SysOssVo oss = ossService.upload(file, JsonUtils.parseObject(ossExtJson, SysOssExt.class));
        SysOssUploadVo uploadVo = new SysOssUploadVo(oss.getUrl(), oss.getOriginalName(), oss.getOssId().toString());
        return R.ok(uploadVo);
    }

    /**
     * 下载OSS对象
     *
     * @param ossId OSS对象ID
     * @throws IOException IO 异常
     */
    @SaCheckPermission("system:oss:download")
    @GetMapping("/download/{ossId}")
    public ResponseEntity<byte[]> download(@PathVariable Long ossId) throws IOException {
        return ossService.download(ossId);
    }

    /**
     * 删除OSS对象存储
     *
     * @param ossIds OSS对象ID串
     * @return 操作结果
     */
    @SaCheckPermission("system:oss:remove")
    @Log(title = "OSS对象存储", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ossIds}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ossIds) {
        return toAjax(ossService.deleteWithValidByIds(List.of(ossIds), true));
    }

    /**
     * OSS 上传响应对象。
     *
     * @param url      文件访问地址
     * @param fileName 原始文件名
     * @param ossId    OSS 对象 ID
     */
    public record SysOssUploadVo(String url, String fileName, String ossId) {
    }
}
