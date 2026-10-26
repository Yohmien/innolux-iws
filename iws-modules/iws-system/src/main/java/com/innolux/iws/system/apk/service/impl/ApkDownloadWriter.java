/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 将 APK 对象流直接写入 HTTP 响应；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service.impl;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.oss.client.OssClient;
import com.innolux.iws.common.oss.io.OutputStreamDownloadSubscriber;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.domain.vo.SysOssVo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * APK HTTP 下载写出器。
 *
 * <p>所有数据和 OSS 客户端校验由调用方在进入本类前完成。
 * 本类在开始写流前一次性设置 APK MIME 与 Content-Length，分块订阅对象存储输出，
 * 不在内存中聚合文件。</p>
 *
 * @author YONGJIE.PENG
 */
final class ApkDownloadWriter {

    static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";

    private ApkDownloadWriter() {
    }

    /**
     * 写出已验证的 APK 发布版本。
     *
     * @param response 当前 HTTP 响应
     * @param release  发布版本
     * @param oss      对象存储元数据
     * @param client   已解析的 OSS 客户端
     * @throws IOException 写出响应失败
     */
    static void write(HttpServletResponse response, ApkRelease release, SysOssVo oss,
                      OssClient client) throws IOException {
        validate(release, oss);
        String contentDisposition = ContentDisposition.attachment()
            .filename(displayName(release, oss), StandardCharsets.UTF_8).build().toString();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(APK_CONTENT_TYPE);
        response.setContentLengthLong(release.getFileSize());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=0, must-revalidate");
        client.download(oss.getFileName(), OutputStreamDownloadSubscriber.create(response.getOutputStream()));
    }

    private static void validate(ApkRelease release, SysOssVo oss) {
        if (release == null) {
            throw new ServiceException("APK 发布版本不存在");
        }
        if (release.getFileSize() == null || release.getFileSize() <= 0) {
            throw new ServiceException("APK 文件大小无效，不能下载");
        }
        if (oss == null || isBlank(oss.getService()) || isBlank(oss.getFileName())) {
            throw new ServiceException("APK 文件存储信息不存在");
        }
    }

    private static String displayName(ApkRelease release, SysOssVo oss) {
        String originalName = oss.getOriginalName();
        String fileName = isBlank(originalName)
            ? release.getAppName() + "-" + release.getVersionName() + ".apk"
            : originalName;
        return fileName.replace('\r', '_').replace('\n', '_');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
