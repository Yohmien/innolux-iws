/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 验证 APK 分块下载长度、类型和错误前置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service.impl;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.oss.client.OssClient;
import com.innolux.iws.common.oss.io.OutputStreamDownloadSubscriber;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.domain.vo.SysOssVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * APK 下载写出测试。
 */
@DisplayName("APK 下载写出")
@Tag("dev")
class ApkDownloadWriterTest {

    @Test
    @DisplayName("分块写出并设置 APK 长度与类型")
    void streamsWithContentLengthAndApkType() throws Exception {
        ApkRelease release = release(6L);
        SysOssVo oss = oss();
        OssClient client = mock(OssClient.class);
        doAnswer(invocation -> {
            OutputStreamDownloadSubscriber subscriber = invocation.getArgument(1);
            subscriber.accept(ByteBuffer.wrap("APK".getBytes(StandardCharsets.UTF_8)));
            subscriber.accept(ByteBuffer.wrap("123".getBytes(StandardCharsets.UTF_8)));
            return null;
        }).when(client).download(eq("apk/demo.apk"), any(OutputStreamDownloadSubscriber.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApkDownloadWriter.write(response, release, oss, client);

        assertEquals(6L, response.getContentLength());
        assertEquals(ApkDownloadWriter.APK_CONTENT_TYPE, response.getContentType());
        assertEquals("APK123", response.getContentAsString());
        assertTrue(response.getHeader(HttpHeaders.CONTENT_DISPOSITION).contains("demo.apk"));
    }

    @Test
    @DisplayName("文件长度无效时在提交响应前失败")
    void rejectsInvalidLengthBeforeResponse() {
        ApkRelease release = release(0L);
        OssClient client = mock(OssClient.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServiceException.class,
            () -> ApkDownloadWriter.write(response, release, oss(), client));

        assertFalse(response.isCommitted());
        assertEquals(0L, response.getContentLength());
        assertNull(response.getContentType());
        verifyNoInteractions(client);
    }

    private static ApkRelease release(long fileSize) {
        ApkRelease release = new ApkRelease();
        release.setAppName("Demo");
        release.setVersionName("1.0");
        release.setFileSize(fileSize);
        return release;
    }

    private static SysOssVo oss() {
        SysOssVo oss = new SysOssVo();
        oss.setService("local");
        oss.setFileName("apk/demo.apk");
        oss.setOriginalName("demo.apk");
        return oss;
    }
}
