/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 验证 APK 上传去重、并发补偿和发布失败后状态回读；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.oss.exception.S3StorageException;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import com.innolux.iws.system.apk.parse.ApkMetadata;
import com.innolux.iws.system.apk.parse.ApkMetadataParser;
import com.innolux.iws.system.apk.process.ApkPublicationProcess;
import com.innolux.iws.system.apk.service.impl.ApkReleaseCommandServiceImpl;
import com.innolux.iws.system.apk.service.impl.ApkReleaseMetadataWriter;
import com.innolux.iws.system.domain.SysOssExt;
import com.innolux.iws.system.domain.vo.SysOssVo;
import com.innolux.iws.system.service.ISysOssService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * APK 上传命令测试。
 */
@DisplayName("APK 上传命令")
@Tag("dev")
class ApkReleaseCommandServiceTest {

    private static final byte[] CONTENT = "apk-content".getBytes(StandardCharsets.UTF_8);

    private ApkReleaseMapper releaseMapper;

    private ApkMetadataParser metadataParser;

    private ISysOssService ossService;

    private ApkReleaseMetadataWriter metadataWriter;

    private ApkPublicationProcess publicationProcess;

    private ApkReleaseCommandServiceImpl service;

    @BeforeAll
    static void initializeMybatis() {
        ApkMybatisTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        releaseMapper = mock(ApkReleaseMapper.class);
        metadataParser = mock(ApkMetadataParser.class);
        ossService = mock(ISysOssService.class);
        metadataWriter = mock(ApkReleaseMetadataWriter.class);
        publicationProcess = mock(ApkPublicationProcess.class);
        service = new ApkReleaseCommandServiceImpl(releaseMapper, metadataParser, ossService,
            metadataWriter, publicationProcess);
        doAnswer(invocation -> {
            ApkRelease release = invocation.getArgument(0);
            release.setReleaseId(10L);
            return null;
        }).when(metadataWriter).insert(any(ApkRelease.class));
    }

    @Test
    @DisplayName("同包同版本同摘要直接复用旧记录")
    void reusesSameContent() throws Exception {
        ApkRelease existing = existing(sha256(CONTENT));
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(existing);

        ApkReleaseVO result = service.upload(apkFile(), "重复上传");

        assertEquals(10L, result.getReleaseId());
        verify(ossService, never()).upload(any(MultipartFile.class), any(SysOssExt.class), anyString());
        verify(metadataWriter, never()).insert(any());
        verify(publicationProcess, never()).publish(anyLong(), anyString());
    }

    @Test
    @DisplayName("同包同版本不同摘要拒绝冲突")
    void rejectsDifferentContent() throws Exception {
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(existing("0".repeat(64)));

        assertThrows(ServiceException.class, () -> service.upload(apkFile(), null));
        verify(ossService, never()).upload(any(MultipartFile.class), any(SysOssExt.class), anyString());
    }

    @Test
    @DisplayName("对象存储管理端口错误转换为可操作的 APK 上传提示")
    void translatesS3ApiPortError() throws Exception {
        S3StorageException storageFailure = new S3StorageException("S3 upload failed",
            S3Exception.builder()
                .message("S3 API Requests must be made to API port.")
                .statusCode(400)
                .build());
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(null);
        when(ossService.upload(any(MultipartFile.class), any(SysOssExt.class), anyString()))
            .thenThrow(storageFailure);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.upload(apkFile(), null));

        assertEquals("APK 上传失败：对象存储访问站点指向了 MinIO 管理端口，请在对象存储配置中改为 S3 API 端口"
            + "（通常为 9000），并检查反向代理转发。", exception.getMessage());
        assertSame(storageFailure, exception.getCause());
        verify(metadataWriter, never()).insert(any());
    }

    @Test
    @DisplayName("其他对象存储错误使用安全通用提示")
    void translatesOtherStorageError() throws Exception {
        S3StorageException storageFailure = new S3StorageException("connection refused");
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(null);
        when(ossService.upload(any(MultipartFile.class), any(SysOssExt.class), anyString()))
            .thenThrow(storageFailure);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.upload(apkFile(), null));

        assertEquals("APK 上传失败：对象存储写入失败，请检查存储服务连接、访问凭证和桶权限。", exception.getMessage());
        assertSame(storageFailure, exception.getCause());
        verify(metadataWriter, never()).insert(any());
    }

    @Test
    @DisplayName("两参上传默认启用强制更新")
    void defaultsForceUpdateToTrueOnUpload() throws Exception {
        SysOssVo ownOss = oss(55L);
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(null);
        when(ossService.upload(any(MultipartFile.class), any(SysOssExt.class), anyString())).thenReturn(ownOss);
        when(releaseMapper.selectById(10L)).thenReturn(existing(sha256(CONTENT)));

        service.upload(apkFile(), null);

        ArgumentCaptor<ApkRelease> releaseCaptor = ArgumentCaptor.forClass(ApkRelease.class);
        verify(metadataWriter).insert(releaseCaptor.capture());
        assertTrue(releaseCaptor.getValue().getForceUpdate());
    }

    @Test
    @DisplayName("三参上传保存关闭强制更新")
    void storesExplicitForceUpdateOnUpload() throws Exception {
        SysOssVo ownOss = oss(55L);
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(null);
        when(ossService.upload(any(MultipartFile.class), any(SysOssExt.class), anyString())).thenReturn(ownOss);
        when(releaseMapper.selectById(10L)).thenReturn(existing(sha256(CONTENT)));

        service.upload(apkFile(), null, false);

        ArgumentCaptor<ApkRelease> releaseCaptor = ArgumentCaptor.forClass(ApkRelease.class);
        verify(metadataWriter).insert(releaseCaptor.capture());
        assertFalse(releaseCaptor.getValue().getForceUpdate());
    }

    @Test
    @DisplayName("按版本 ID 修改强制更新策略")
    void updatesForceUpdateByReleaseId() {
        when(releaseMapper.update(isNull(), any())).thenReturn(1);

        service.updateForceUpdate(10L, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ApkRelease>> wrapperCaptor =
            ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(releaseMapper).update(isNull(), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("release_id"));
        assertTrue(wrapperCaptor.getValue().getSqlSet().contains("force_update"));
        verify(releaseMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("策略未变化时按成功处理")
    void treatsUnchangedForceUpdateAsSuccess() {
        when(releaseMapper.update(isNull(), any())).thenReturn(0);
        when(releaseMapper.selectById(10L)).thenReturn(existing(sha256(CONTENT)));

        service.updateForceUpdate(10L, true);

        verify(releaseMapper).selectById(10L);
    }

    @Test
    @DisplayName("回读策略与目标不一致时明确失败")
    void rejectsUnappliedForceUpdate() {
        when(releaseMapper.update(isNull(), any())).thenReturn(0);
        when(releaseMapper.selectById(10L)).thenReturn(existing(sha256(CONTENT)));

        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.updateForceUpdate(10L, false));

        assertTrue(exception.getMessage().contains("强制更新设置未生效"));
    }

    @Test
    @DisplayName("修改不存在的版本明确报错")
    void rejectsMissingReleaseOnForceUpdate() {
        when(releaseMapper.update(isNull(), any())).thenReturn(0);
        when(releaseMapper.selectById(10L)).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class,
            () -> service.updateForceUpdate(10L, false));

        assertTrue(exception.getMessage().contains("APK 发布版本不存在"));
    }

    @Test
    @DisplayName("并发唯一冲突只清理本次新 OSS 并复用胜出记录")
    void cleansOwnOssOnConcurrentDuplicate() throws Exception {
        ApkRelease winner = existing(sha256(CONTENT));
        SysOssVo ownOss = oss(55L);
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(null, winner);
        when(ossService.upload(any(MultipartFile.class), any(SysOssExt.class), anyString())).thenReturn(ownOss);
        doThrow(new DataIntegrityViolationException("duplicate"))
            .when(metadataWriter).insert(any(ApkRelease.class));

        ApkReleaseVO result = service.upload(apkFile(), null);

        assertEquals(10L, result.getReleaseId());
        verify(ossService).deleteWithValidByIds(List.of(55L), false);
    }

    @Test
    @DisplayName("发布失败保留待重试状态并回读最新记录")
    void keepsUploadedWhenPublicationFails() throws Exception {
        SysOssVo ownOss = oss(55L);
        ApkRelease uploaded = existing(sha256(CONTENT));
        uploaded.setStatus("UPLOADED");
        when(metadataParser.parse(any(File.class))).thenReturn(metadata());
        when(releaseMapper.selectOne(any())).thenReturn(null);
        when(ossService.upload(any(MultipartFile.class), any(SysOssExt.class), anyString())).thenReturn(ownOss);
        when(releaseMapper.selectById(10L)).thenReturn(uploaded);
        when(publicationProcess.publish(anyLong(), anyString()))
            .thenThrow(new ServiceException("计划执行失败"));

        ApkReleaseVO result = service.upload(apkFile(), null);

        assertEquals("UPLOADED", result.getStatus());
        verify(publicationProcess).publish(anyLong(), anyString());
        verify(ossService, never()).deleteWithValidByIds(any(), any());
    }

    @Test
    @DisplayName("手动重试发布失败向调用方抛错")
    void retryPropagatesPublicationFailure() {
        ApkRelease uploaded = existing(sha256(CONTENT));
        uploaded.setStatus("UPLOADED");
        when(releaseMapper.selectById(10L)).thenReturn(uploaded);
        when(publicationProcess.publish(anyLong(), anyString()))
            .thenThrow(new ServiceException("计划执行失败"));

        assertThrows(ServiceException.class, () -> service.retryPublish(10L));
    }

    @Test
    @DisplayName("手动重试成功后返回重新查询的已发布版本")
    void retryReturnsPublishedRelease() {
        ApkRelease uploaded = existing(sha256(CONTENT));
        uploaded.setStatus("UPLOADED");
        ApkRelease published = existing(sha256(CONTENT));
        published.setStatus("PUBLISHED");
        published.setProcessInstanceId(20L);
        when(releaseMapper.selectById(10L)).thenReturn(uploaded, published);
        when(publicationProcess.publish(10L, uploaded.getSha256())).thenReturn("20");

        ApkReleaseVO result = service.retryPublish(10L);

        assertEquals("PUBLISHED", result.getStatus());
        assertEquals("20", result.getProcessInstanceId().toString());
    }

    private static MockMultipartFile apkFile() {
        return new MockMultipartFile("file", "renamed.apk", "application/octet-stream", CONTENT);
    }

    private static ApkMetadata metadata() {
        return new ApkMetadata("Demo", "com.demo.app", "1.0.0", 100L);
    }

    private static ApkRelease existing(String sha256) {
        ApkRelease release = new ApkRelease();
        release.setReleaseId(10L);
        release.setAppName("Demo");
        release.setPackageName("com.demo.app");
        release.setVersionName("1.0.0");
        release.setVersionCode(100L);
        release.setSha256(sha256);
        release.setOssId(20L);
        release.setFileSize((long) CONTENT.length);
        release.setStatus("UPLOADED");
        release.setForceUpdate(true);
        return release;
    }

    private static SysOssVo oss(long ossId) {
        SysOssVo oss = new SysOssVo();
        oss.setOssId(ossId);
        oss.setService("local");
        oss.setFileName("apk/demo.apk");
        return oss;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
