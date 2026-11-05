/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : APK 应用、版本与公开下载查询实现；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.oss.client.OssClient;
import com.innolux.iws.common.oss.factory.OssFactory;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.domain.vo.ApkApplicationRow;
import com.innolux.iws.system.apk.domain.vo.ApkApplicationVO;
import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import com.innolux.iws.system.apk.enums.ApkReleaseStatus;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import com.innolux.iws.system.apk.service.IApkReleaseQueryService;
import com.innolux.iws.system.domain.vo.SysOssVo;
import com.innolux.iws.system.service.ISysOssService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * APK 应用、版本与公开下载查询实现。
 *
 * @author YONGJIE.PENG
 */
@Service
@RequiredArgsConstructor
public class ApkReleaseQueryServiceImpl implements IApkReleaseQueryService {

    private static final Logger log = LoggerFactory.getLogger(ApkReleaseQueryServiceImpl.class);

    private static final String PUBLISHED = ApkReleaseStatus.PUBLISHED.name();

    private static final int SINGLE_RESULT_PAGE_SIZE = 1;

    private static final int AMBIGUITY_CHECK_PAGE_SIZE = 2;

    private final ApkReleaseMapper releaseMapper;

    private final ISysOssService ossService;

    private final ObjectProvider<ApkDownloadAccessRecorder> accessRecorderProvider;

    /**
     * 分页查询应用汇总。
     *
     * <p>versionCount 统计同包全部历史；appName 过滤命中该包任一历史名称，
     * 展示名称与最新版本都来自最终选中的版本。</p>
     */
    @Override
    public PageResult<ApkApplicationVO> queryApplications(String packageName, String appName,
                                                          PageQuery pageQuery) {
        String normalizedPackageName = trimToNull(packageName);
        String normalizedAppName = trimToNull(appName);
        Page<ApkApplicationRow> page = releaseMapper.selectApplicationPage(pageQuery.build(),
            normalizedPackageName, normalizedAppName);
        return PageResult.build(toApplicationRows(page.getRecords()), page.getTotal());
    }

    @Override
    public PageResult<ApkReleaseVO> queryHistory(String packageName, PageQuery pageQuery) {
        String normalizedPackageName = requireText(packageName, "请指定 APK 安装包名称");
        Page<ApkRelease> page = releaseMapper.selectPage(pageQuery.build(),
            historyWrapper(normalizedPackageName, null));
        return toReleasePage(page);
    }

    @Override
    public ApkReleaseVO queryLatestPublished(String packageName, String appName) {
        String normalizedPackageName = trimToNull(packageName);
        String normalizedAppName = trimToNull(appName);
        if (normalizedPackageName == null && normalizedAppName == null) {
            throw new ServiceException("packageName 与 appName 至少提供一个");
        }
        if (normalizedPackageName != null) {
            if (normalizedAppName != null && countReleases(normalizedPackageName, normalizedAppName, null) == 0) {
                throw new ServiceException("packageName 与 appName 不匹配");
            }
            return toRelease(findLatestPublishedOrNull(normalizedPackageName));
        }
        long packageCount = releaseMapper.countDistinctPackagesByAppName(normalizedAppName, PUBLISHED);
        if (packageCount == 0) {
            return null;
        }
        if (packageCount > 1) {
            throw new ServiceException("应用名称对应多个安装包，请指定 packageName: " + normalizedAppName);
        }
        return toRelease(findLatestPublishedOrNull(findPublishedPackageByAppName(normalizedAppName)));
    }

    @Override
    public PageResult<ApkReleaseVO> queryPublishedHistory(String packageName, String appName,
                                                          PageQuery pageQuery) {
        String resolvedPackageName = resolvePublishedPackage(packageName, appName);
        Page<ApkRelease> page = releaseMapper.selectPage(pageQuery.build(),
            historyWrapper(resolvedPackageName, PUBLISHED));
        return toReleasePage(page);
    }

    @Override
    public void downloadPublished(String packageName, String appName, String versionCode,
                                  String versionName, String currentVersionCode,
                                  String currentVersionName, HttpServletResponse response) throws IOException {
        LocalDateTime startedAt = LocalDateTime.now();
        ApkDownloadAccessRecorder.Source source = ApkDownloadAccessRecorder.currentSource();
        ApkRelease release = null;
        boolean success = false;
        boolean committed = false;
        String failure = null;
        int httpStatus = HttpServletResponse.SC_OK;
        try {
            String resolvedPackageName = resolvePublishedPackage(packageName, appName);
            release = resolvePublishedVersion(resolvedPackageName, versionCode, versionName);
            if (release.getOssId() == null) {
                throw new ServiceException("APK 版本未关联文件，不能下载");
            }
            SysOssVo oss = ossService.getById(release.getOssId());
            if (oss == null) {
                throw new ServiceException("APK 文件记录不存在，不能下载");
            }
            OssClient client = OssFactory.instance(oss.getService());
            ApkDownloadWriter.write(response, release, oss, client);
            success = true;
        } catch (IOException ex) {
            failure = ex.getMessage();
            committed = response.isCommitted();
            httpStatus = response.getStatus();
            throw ex;
        } catch (RuntimeException ex) {
            failure = ex.getMessage();
            committed = response.isCommitted();
            httpStatus = response.getStatus();
            throw ex;
        } finally {
            recordAccess(new ApkDownloadAccessRecorder.Access(
                release == null ? trimToNull(packageName) : release.getPackageName(),
                currentVersion(currentVersionName, currentVersionCode),
                release == null ? targetVersion(versionName, versionCode)
                    : release.getVersionName() + "(" + release.getVersionCode() + ")",
                release == null ? null : release.getVersionCode(),
                release == null ? null : release.getProcessInstanceId(),
                release == null ? null : release.getFileSize(),
                success, committed, failure, httpStatus, source.clientIp(), source.userAgent(),
                startedAt, LocalDateTime.now()));
            if (success && release != null) {
                increaseDownloadCount(release.getReleaseId());
            }
        }
    }

    private void recordAccess(ApkDownloadAccessRecorder.Access access) {
        ApkDownloadAccessRecorder recorder = accessRecorderProvider.getIfAvailable();
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(access);
        } catch (RuntimeException ex) {
            log.warn("APK 下载访问记录写入失败: packageName={}, versionCode={}",
                access.packageName(), access.versionCode(), ex);
        }
    }

    private void increaseDownloadCount(Long releaseId) {
        if (releaseId == null) {
            return;
        }
        try {
            releaseMapper.update(null, Wrappers.<ApkRelease>lambdaUpdate()
                .eq(ApkRelease::getReleaseId, releaseId)
                .setSql("download_count = download_count + 1"));
        } catch (RuntimeException ex) {
            log.warn("APK 下载计数更新失败: releaseId={}", releaseId, ex);
        }
    }

    private static String currentVersion(String versionName, String versionCode) {
        String name = trimToNull(versionName);
        String code = trimToNull(versionCode);
        if (name == null && code == null) {
            return null;
        }
        if (code == null) {
            return name;
        }
        return name == null ? code : name + "(" + code + ")";
    }

    private static String targetVersion(String versionName, String versionCode) {
        String name = trimToNull(versionName);
        String code = trimToNull(versionCode);
        if (name == null && code == null) {
            return "最新已发布版本";
        }
        if (code == null) {
            return name;
        }
        return name == null ? code : name + "(" + code + ")";
    }

    private String resolvePublishedPackage(String packageName, String appName) {
        String normalizedPackageName = trimToNull(packageName);
        String normalizedAppName = trimToNull(appName);
        if (normalizedPackageName == null && normalizedAppName == null) {
            throw new ServiceException("packageName 与 appName 至少提供一个");
        }
        if (normalizedAppName != null && normalizedPackageName == null) {
            long packageCount = releaseMapper.countDistinctPackagesByAppName(normalizedAppName, PUBLISHED);
            if (packageCount == 0) {
                throw new ServiceException("未找到已发布的 APK 应用: " + normalizedAppName);
            }
            if (packageCount > 1) {
                throw new ServiceException("应用名称对应多个安装包，请指定 packageName: " + normalizedAppName);
            }
        }
        if (normalizedPackageName == null) {
            return findPublishedPackageByAppName(normalizedAppName);
        }
        long releaseCount = countPublishedReleases(normalizedPackageName, normalizedAppName);
        if (releaseCount == 0) {
            if (normalizedAppName != null) {
                throw new ServiceException("packageName 与 appName 不匹配或版本未发布");
            }
            throw new ServiceException("未找到已发布的 APK 安装包: " + normalizedPackageName);
        }
        return normalizedPackageName;
    }

    private long countPublishedReleases(String packageName, String appName) {
        return countReleases(packageName, appName, PUBLISHED);
    }

    private long countReleases(String packageName, String appName, String status) {
        Long count = releaseMapper.selectCount(Wrappers.<ApkRelease>lambdaQuery()
            .eq(ApkRelease::getPackageName, packageName)
            .eq(appName != null, ApkRelease::getAppName, appName)
            .eq(status != null, ApkRelease::getStatus, status));
        return count == null ? 0 : count;
    }

    private String findPublishedPackageByAppName(String appName) {
        Page<ApkRelease> page = selectFixedPage(1, Wrappers.<ApkRelease>lambdaQuery()
            .eq(ApkRelease::getAppName, appName)
            .eq(ApkRelease::getStatus, PUBLISHED)
            .orderByDesc(ApkRelease::getVersionCode)
            .orderByDesc(ApkRelease::getReleaseId));
        if (page.getRecords().isEmpty()) {
            throw new ServiceException("未找到已发布的 APK 应用: " + appName);
        }
        return page.getRecords().getFirst().getPackageName();
    }

    private ApkRelease resolvePublishedVersion(String packageName, String versionCode, String versionName) {
        String normalizedVersionName = trimToNull(versionName);
        Long parsedVersionCode = parseVersionCode(versionCode);
        if (parsedVersionCode != null) {
            ApkRelease release = findPublishedByCode(packageName, parsedVersionCode);
            if (normalizedVersionName != null && !normalizedVersionName.equals(release.getVersionName())) {
                throw new ServiceException("versionCode 与 versionName 不匹配");
            }
            return release;
        }
        if (normalizedVersionName != null) {
            return findUniquePublishedByName(packageName, normalizedVersionName);
        }
        return findLatestPublished(packageName);
    }

    private ApkRelease findPublishedByCode(String packageName, long versionCode) {
        Page<ApkRelease> page = selectFixedPage(SINGLE_RESULT_PAGE_SIZE, Wrappers.<ApkRelease>lambdaQuery()
            .eq(ApkRelease::getPackageName, packageName)
            .eq(ApkRelease::getVersionCode, versionCode)
            .eq(ApkRelease::getStatus, PUBLISHED));
        if (page.getRecords().isEmpty()) {
            throw new ServiceException("未找到指定版本的已发布 APK: " + versionCode);
        }
        return page.getRecords().getFirst();
    }

    private ApkRelease findUniquePublishedByName(String packageName, String versionName) {
        Page<ApkRelease> page = selectFixedPage(AMBIGUITY_CHECK_PAGE_SIZE, Wrappers.<ApkRelease>lambdaQuery()
            .eq(ApkRelease::getPackageName, packageName)
            .eq(ApkRelease::getVersionName, versionName)
            .eq(ApkRelease::getStatus, PUBLISHED)
            .orderByDesc(ApkRelease::getVersionCode)
            .orderByDesc(ApkRelease::getReleaseId));
        if (page.getRecords().isEmpty()) {
            throw new ServiceException("未找到指定版本的已发布 APK: " + versionName);
        }
        if (page.getRecords().size() > 1) {
            throw new ServiceException("版本名称对应多个已发布版本，请使用 versionCode: " + versionName);
        }
        return page.getRecords().getFirst();
    }

    private ApkRelease findLatestPublished(String packageName) {
        ApkRelease release = findLatestPublishedOrNull(packageName);
        if (release == null) {
            throw new ServiceException("未找到已发布的 APK 安装包: " + packageName);
        }
        return release;
    }

    private ApkRelease findLatestPublishedOrNull(String packageName) {
        Page<ApkRelease> page = selectFixedPage(SINGLE_RESULT_PAGE_SIZE, Wrappers.<ApkRelease>lambdaQuery()
            .eq(ApkRelease::getPackageName, packageName)
            .eq(ApkRelease::getStatus, PUBLISHED)
            .orderByDesc(ApkRelease::getVersionCode)
            .orderByDesc(ApkRelease::getReleaseId));
        return page.getRecords().isEmpty() ? null : page.getRecords().getFirst();
    }

    private Page<ApkRelease> selectFixedPage(int pageSize, LambdaQueryWrapper<ApkRelease> wrapper) {
        return releaseMapper.selectPage(new Page<>(1, pageSize, false), wrapper);
    }

    private static LambdaQueryWrapper<ApkRelease> historyWrapper(String packageName, String status) {
        return Wrappers.<ApkRelease>lambdaQuery()
            .eq(ApkRelease::getPackageName, packageName)
            .eq(status != null, ApkRelease::getStatus, status)
            .orderByDesc(ApkRelease::getVersionCode)
            .orderByDesc(ApkRelease::getReleaseId);
    }

    private static PageResult<ApkReleaseVO> toReleasePage(Page<ApkRelease> page) {
        List<ApkReleaseVO> rows = page.getRecords().stream().map(ApkReleaseVO::from).toList();
        return PageResult.build(rows, page.getTotal());
    }

    private static ApkReleaseVO toRelease(ApkRelease release) {
        return release == null ? null : ApkReleaseVO.from(release);
    }

    private List<ApkApplicationVO> toApplicationRows(List<ApkApplicationRow> rows) {
        List<Long> releaseIds = rows.stream().map(ApkApplicationRow::getLatestReleaseId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, ApkRelease> releases = releaseIds.isEmpty() ? Map.of()
            : releaseMapper.selectByIds(releaseIds).stream()
                .collect(Collectors.toMap(ApkRelease::getReleaseId, Function.identity()));
        return rows.stream().map(row -> toApplication(row, releases)).toList();
    }

    private static ApkApplicationVO toApplication(ApkApplicationRow row, Map<Long, ApkRelease> releases) {
        ApkApplicationVO application = new ApkApplicationVO();
        application.setPackageName(row.getPackageName());
        application.setAppName(row.getAppName());
        application.setVersionCount(row.getVersionCount());
        ApkRelease latestRelease = releases.get(row.getLatestReleaseId());
        if (latestRelease == null) {
            throw new ServiceException("APK 应用最新版本不存在: " + row.getLatestReleaseId());
        }
        application.setLatestVersion(ApkReleaseVO.from(latestRelease));
        return application;
    }

    private static Long parseVersionCode(String versionCode) {
        String normalized = trimToNull(versionCode);
        if (normalized == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < 0) {
                throw new ServiceException("versionCode 不能为负数");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new ServiceException("versionCode 必须为整数: " + normalized);
        }
    }

    private static String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new ServiceException(message);
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
