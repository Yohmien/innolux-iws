/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 上传、版本去重、发布重试与计划初始化实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import com.innolux.iws.system.apk.enums.ApkReleaseStatus;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import com.innolux.iws.system.apk.parse.ApkMetadata;
import com.innolux.iws.system.apk.parse.ApkMetadataParser;
import com.innolux.iws.system.apk.process.ApkPublicationProcess;
import com.innolux.iws.system.apk.process.PlanProvisioningResult;
import com.innolux.iws.system.apk.service.IApkReleaseCommandService;
import com.innolux.iws.system.domain.SysOssExt;
import com.innolux.iws.system.domain.vo.SysOssVo;
import com.innolux.iws.system.service.ISysOssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * APK 上传、版本去重、发布重试与计划初始化实现。
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApkReleaseCommandServiceImpl implements IApkReleaseCommandService {

    private static final long MAX_APK_BYTES = 512L * 1024L * 1024L;

    private static final String APK_SUFFIX = ".apk";

    private static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";

    private static final String APK_BUSINESS_TYPE = "APK_RELEASE";

    private static final String APK_OSS_PREFIX = "apk/";

    private static final int MAX_APP_NAME_LENGTH = 200;

    private static final int MAX_PACKAGE_NAME_LENGTH = 255;

    private static final int MAX_VERSION_NAME_LENGTH = 100;

    private static final int MAX_REMARK_LENGTH = 500;

    private static final int HASH_BUFFER_SIZE = 8192;

    private static final String PLAN_INIT_COMMAND_KEY = "APK_RELEASE_PLAN_V1";

    private static final String PLAN_INIT_REASON = "初始化或核验 APK 发布计划 V1";

    private final ApkReleaseMapper releaseMapper;

    private final ApkMetadataParser metadataParser;

    private final ISysOssService ossService;

    private final ApkReleaseMetadataWriter metadataWriter;

    private final ApkPublicationProcess publicationProcess;

    @Override
    public ApkReleaseVO upload(MultipartFile file, String remark) {
        validateFile(file);
        String normalizedRemark = validateRemark(remark);
        Path tempFile = createTempFile(file);
        try {
            return uploadValidated(tempFile, normalizedRemark);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    @Override
    public ApkReleaseVO retryPublish(long releaseId) {
        ApkRelease release = releaseMapper.selectById(releaseId);
        if (release == null) {
            throw new ServiceException("APK 发布版本不存在: " + releaseId);
        }
        if (ApkReleaseStatus.isPublished(release.getStatus())) {
            return ApkReleaseVO.from(release);
        }
        if (!ApkReleaseStatus.UPLOADED.name().equals(release.getStatus())) {
            throw new ServiceException("APK 版本状态不允许重试发布: " + release.getStatus());
        }
        publicationProcess.publish(releaseId, release.getSha256());
        ApkRelease latest = releaseMapper.selectById(releaseId);
        if (latest == null || !ApkReleaseStatus.isPublished(latest.getStatus())) {
            throw new ServiceException("APK 计划返回成功，但本地版本未进入已发布状态: " + releaseId);
        }
        return ApkReleaseVO.from(latest);
    }

    @Override
    public void initializePlan() {
        PlanProvisioningResult result = publicationProcess.initialize(PLAN_INIT_COMMAND_KEY, PLAN_INIT_REASON);
        log.info("APK 发布计划已核验: {}", result);
    }

    private ApkReleaseVO uploadValidated(Path tempFile, String remark) {
        ApkMetadata metadata = parseMetadata(tempFile);
        String sha256 = sha256(tempFile);
        long fileSize = fileSize(tempFile);
        ApkRelease existing = findByVersion(metadata.packageName(), metadata.versionCode());
        if (existing != null) {
            return reuseOrReject(existing, sha256);
        }

        SysOssVo oss = uploadToOss(tempFile, metadata, remark);
        ApkRelease release = newRelease(metadata, remark, oss, sha256, fileSize);
        try {
            metadataWriter.insert(release);
        } catch (DataIntegrityViolationException ex) {
            return reconcileConcurrentDuplicate(metadata, sha256, oss);
        } catch (RuntimeException ex) {
            cleanupNewOss(oss.getOssId());
            throw ex;
        }
        return ApkReleaseVO.from(publishAndRefresh(release));
    }

    private ApkReleaseVO reconcileConcurrentDuplicate(ApkMetadata metadata, String sha256, SysOssVo oss) {
        ApkRelease existing = findByVersion(metadata.packageName(), metadata.versionCode());
        cleanupNewOss(oss.getOssId());
        if (existing != null) {
            return reuseOrReject(existing, sha256);
        }
        throw new ServiceException("APK 版本写入冲突，请重新查询后重试");
    }

    private ApkReleaseVO reuseOrReject(ApkRelease existing, String sha256) {
        if (!sha256.equals(existing.getSha256())) {
            throw new ServiceException("同一安装包和版本号已存在不同内容的 APK");
        }
        log.info("APK 内容已存在，复用版本: releaseId={}", existing.getReleaseId());
        return ApkReleaseVO.from(existing);
    }

    private SysOssVo uploadToOss(Path tempFile, ApkMetadata metadata, String remark) {
        String fileName = metadata.packageName() + "-" + metadata.versionCode() + APK_SUFFIX;
        SysOssExt ext = new SysOssExt();
        ext.setBizType(APK_BUSINESS_TYPE);
        ext.setSource("systemApkUpload");
        ext.setRemark(remark);
        ext.setRefType(APK_BUSINESS_TYPE);
        String prefix = APK_OSS_PREFIX + metadata.packageName() + "/" + metadata.versionCode();
        MultipartFile uploadFile = new ApkTempMultipartFile(tempFile, fileName, APK_CONTENT_TYPE);
        SysOssVo oss = ossService.upload(uploadFile, ext, prefix);
        if (oss == null || oss.getOssId() == null) {
            throw new ServiceException("APK 文件上传未返回有效记录");
        }
        return oss;
    }

    private ApkRelease publishAndRefresh(ApkRelease release) {
        try {
            publicationProcess.publish(release.getReleaseId(), release.getSha256());
        } catch (RuntimeException ex) {
            log.error("APK 发布失败，版本保持待重试: releaseId={}", release.getReleaseId(), ex);
        }
        ApkRelease latest = releaseMapper.selectById(release.getReleaseId());
        if (latest == null) {
            throw new ServiceException("APK 版本写入后无法重新读取: " + release.getReleaseId());
        }
        return latest;
    }

    private ApkRelease findByVersion(String packageName, Long versionCode) {
        return releaseMapper.selectOne(Wrappers.<ApkRelease>lambdaQuery()
            .eq(ApkRelease::getPackageName, packageName)
            .eq(ApkRelease::getVersionCode, versionCode));
    }

    private static ApkRelease newRelease(ApkMetadata metadata, String remark, SysOssVo oss,
                                         String sha256, long fileSize) {
        ApkRelease release = new ApkRelease();
        release.setAppName(metadata.appName());
        release.setPackageName(metadata.packageName());
        release.setVersionName(metadata.versionName());
        release.setVersionCode(metadata.versionCode());
        release.setSha256(sha256);
        release.setFileSize(fileSize);
        release.setOssId(oss.getOssId());
        release.setStatus(ApkReleaseStatus.UPLOADED.name());
        release.setRemark(remark);
        return release;
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("APK 文件不能为空");
        }
        if (file.getSize() > MAX_APK_BYTES) {
            throw new ServiceException("APK 文件不能超过 512MB");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(APK_SUFFIX)) {
            throw new ServiceException("仅允许上传 .apk 文件");
        }
    }

    private static String validateRemark(String remark) {
        String normalized = remark == null ? null : remark.trim();
        if (normalized != null && normalized.length() > MAX_REMARK_LENGTH) {
            throw new ServiceException("APK 备注不能超过 500 个字符");
        }
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    private static Path createTempFile(MultipartFile file) {
        Path path = null;
        try {
            path = Files.createTempFile("iws-apk-", APK_SUFFIX);
            file.transferTo(path);
            if (Files.size(path) > MAX_APK_BYTES) {
                throw new ServiceException("APK 文件不能超过 512MB");
            }
            return path;
        } catch (IOException | RuntimeException ex) {
            if (path != null) {
                deleteTempFile(path);
            }
            if (ex instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("APK 临时文件保存失败: " + ex.getMessage(), ex);
        }
    }

    private ApkMetadata parseMetadata(Path tempFile) {
        try {
            ApkMetadata metadata = metadataParser.parse(tempFile.toFile());
            validateMetadataLength(metadata);
            return metadata;
        } catch (IOException ex) {
            throw new ServiceException("APK 文件结构无法解析: " + ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("APK 元数据无效: " + ex.getMessage(), ex);
        }
    }

    private static void validateMetadataLength(ApkMetadata metadata) {
        if (metadata.appName().length() > MAX_APP_NAME_LENGTH) {
            throw new ServiceException("APK 应用名称不能超过 200 个字符");
        }
        if (metadata.packageName().length() > MAX_PACKAGE_NAME_LENGTH) {
            throw new ServiceException("APK 包名不能超过 255 个字符");
        }
        if (metadata.versionName().length() > MAX_VERSION_NAME_LENGTH) {
            throw new ServiceException("APK 版本名称不能超过 100 个字符");
        }
    }

    private static String sha256(Path file) {
        try (InputStream inputStream = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[HASH_BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new ServiceException("APK 文件摘要计算失败: " + ex.getMessage(), ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new ServiceException("当前运行环境不支持 SHA-256", ex);
        }
    }

    private static long fileSize(Path file) {
        try {
            long size = Files.size(file);
            if (size <= 0) {
                throw new ServiceException("APK 文件不能为空");
            }
            return size;
        } catch (IOException ex) {
            throw new ServiceException("读取 APK 文件大小失败: " + ex.getMessage(), ex);
        }
    }

    private void cleanupNewOss(Long ossId) {
        if (ossId == null) {
            return;
        }
        try {
            ossService.deleteWithValidByIds(List.of(ossId), false);
        } catch (RuntimeException ex) {
            log.error("APK 上传补偿清理失败，请人工核对本次 OSS: ossId={}", ossId, ex);
        }
    }

    private static void deleteTempFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("APK 临时文件清理失败: {}", file, ex);
        }
    }
}
