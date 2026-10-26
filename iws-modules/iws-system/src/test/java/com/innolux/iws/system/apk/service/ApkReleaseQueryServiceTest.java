/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 验证 APK 公开包选择、版本歧义和分页组装；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.domain.vo.ApkApplicationRow;
import com.innolux.iws.system.apk.domain.vo.ApkApplicationVO;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import com.innolux.iws.system.apk.service.impl.ApkReleaseQueryServiceImpl;
import com.innolux.iws.system.service.ISysOssService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * APK 公开查询规则测试。
 */
@DisplayName("APK 公开查询规则")
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ApkReleaseQueryServiceTest {

    @Mock
    private ApkReleaseMapper releaseMapper;

    @Mock
    private ISysOssService ossService;

    private ApkReleaseQueryServiceImpl service;

    @BeforeAll
    static void initializeMybatis() {
        ApkMybatisTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        service = new ApkReleaseQueryServiceImpl(releaseMapper, ossService);
    }

    @Test
    @DisplayName("appName 单独查询命中多个包时拒绝歧义")
    void rejectsAmbiguousAppName() {
        when(releaseMapper.countDistinctPackagesByAppName("Shared", "PUBLISHED")).thenReturn(2L);

        ServiceException error = assertThrows(ServiceException.class,
            () -> service.queryLatestPublished(null, "Shared"));

        assertTrue(error.getMessage().contains("多个安装包"));
    }

    @Test
    @DisplayName("同时提供包名时不再按 appName 全局拒绝")
    void packageNameDisambiguatesSharedAppName() {
        ApkRelease release = release(9L, "Shared", "com.demo.target", "2.0", 20L, "PUBLISHED");
        when(releaseMapper.selectCount(any())).thenReturn(1L);
        when(releaseMapper.selectPage(any(Page.class), any())).thenReturn(releasePage(1, release));

        assertEquals("2.0", service.queryLatestPublished("com.demo.target", "Shared").getVersionName());
        verify(releaseMapper, never()).countDistinctPackagesByAppName(any(), any());
    }

    @Test
    @DisplayName("packageName 无已发布版本时成功返回空")
    void returnsNullWhenPackageHasNoPublishedRelease() {
        when(releaseMapper.selectPage(any(Page.class), any())).thenReturn(releasePage(0));

        assertNull(service.queryLatestPublished("com.demo.missing", null));
    }

    @Test
    @DisplayName("appName 无已发布版本时成功返回空")
    void returnsNullWhenAppNameHasNoPublishedRelease() {
        when(releaseMapper.countDistinctPackagesByAppName("Missing", "PUBLISHED")).thenReturn(0L);

        assertNull(service.queryLatestPublished(null, "Missing"));
    }

    @Test
    @DisplayName("packageName 与 appName 不匹配仍视为非法查询")
    void rejectedMismatchedPackageAndAppNameForLatest() {
        when(releaseMapper.selectCount(any())).thenReturn(0L);

        ServiceException error = assertThrows(ServiceException.class,
            () -> service.queryLatestPublished("com.demo.missing", "Shared"));

        assertTrue(error.getMessage().contains("不匹配"));
    }

    @Test
    @DisplayName("同包同应用只有待发布版本时返回空且不泄漏")
    void returnsNullForUnpublishedHistory() {
        when(releaseMapper.selectCount(any())).thenReturn(1L);
        when(releaseMapper.selectPage(any(Page.class), any())).thenReturn(releasePage(0));

        assertNull(service.queryLatestPublished("com.demo", "Demo"));
    }

    @Test
    @DisplayName("最新版本查询不吞数据库异常")
    void propagatesRepositoryFailureFromLatest() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(releaseMapper.selectPage(any(Page.class), any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
            () -> service.queryLatestPublished("com.demo", null)));
    }

    @Test
    @DisplayName("包名与应用名不属于同包历史时拒绝")
    void rejectsMismatchedPackageAndAppName() throws IOException {
        when(releaseMapper.selectCount(any())).thenReturn(0L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServiceException.class,
            () -> service.downloadPublished("com.demo.other", "Shared", null, null, response));

        assertNoResponse(response);
        verify(ossService, never()).getById(any());
    }

    @Test
    @DisplayName("版本名称命中多个版本时拒绝并要求 versionCode")
    void rejectsAmbiguousVersionNameBeforeDownload() throws IOException {
        ApkRelease first = release(1L, "Demo", "com.demo", "same", 10L, "PUBLISHED");
        ApkRelease second = release(2L, "Demo", "com.demo", "same", 11L, "PUBLISHED");
        when(releaseMapper.selectCount(any())).thenReturn(1L);
        when(releaseMapper.selectPage(any(Page.class), any())).thenReturn(releasePage(2, first, second));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ServiceException error = assertThrows(ServiceException.class,
            () -> service.downloadPublished("com.demo", "Demo", null, "same", response));

        assertTrue(error.getMessage().contains("versionCode"));
        assertNoResponse(response);
        verify(ossService, never()).getById(any());
    }

    @Test
    @DisplayName("默认版本查询按 versionCode 降序并限一行")
    void selectsLatestPublishedByVersionCode() {
        ApkRelease latest = release(3L, "Demo", "com.demo", "3.0", 30L, "PUBLISHED");
        latest.setForceUpdate(false);
        when(releaseMapper.selectPage(any(Page.class), any())).thenReturn(releasePage(1, latest));

        assertFalse(service.queryLatestPublished("com.demo", null).getForceUpdate());

        ArgumentCaptor<Page<ApkRelease>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper<ApkRelease>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(releaseMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertEquals(1L, pageCaptor.getValue().getSize());
        assertFalse(pageCaptor.getValue().searchCount());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("version_code DESC"));
    }

    @Test
    @DisplayName("应用分页一次批量补齐完整最新版本且统计全部历史")
    void fillsLatestReleaseInOneBatchQuery() {
        ApkApplicationRow row = new ApkApplicationRow();
        row.setPackageName("com.demo");
        row.setAppName("Demo");
        row.setVersionCount(7L);
        row.setLatestReleaseId(99L);
        Page<ApkApplicationRow> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        ApkRelease latest = release(99L, "Demo", "com.demo", "3.0", 30L, "PUBLISHED");
        when(releaseMapper.selectApplicationPage(any(Page.class), eq("com.demo"), isNull())).thenReturn(page);
        when(releaseMapper.selectByIds(List.of(99L))).thenReturn(List.of(latest));

        PageResult<ApkApplicationVO> result = service.queryApplications("com.demo", null, new PageQuery(10, 1));

        ApkApplicationVO application = result.getRows().iterator().next();
        assertEquals(7L, application.getVersionCount());
        assertNotNull(application.getLatestVersion());
        assertEquals("3.0", application.getLatestVersion().getVersionName());
        assertEquals("/app/apk/download?packageName=com.demo&versionCode=30",
            application.getLatestVersion().getDownloadUrl());
        verify(releaseMapper).selectByIds(List.of(99L));
    }

    private static Page<ApkRelease> releasePage(long total, ApkRelease... records) {
        Page<ApkRelease> page = new Page<>(1, 10);
        page.setRecords(List.of(records));
        page.setTotal(total);
        return page;
    }

    private static ApkRelease release(Long id, String appName, String packageName, String versionName,
                                      Long versionCode, String status) {
        ApkRelease release = new ApkRelease();
        release.setReleaseId(id);
        release.setAppName(appName);
        release.setPackageName(packageName);
        release.setVersionName(versionName);
        release.setVersionCode(versionCode);
        release.setStatus(status);
        release.setOssId(10L);
        release.setFileSize(100L);
        release.setForceUpdate(true);
        return release;
    }

    private static void assertNoResponse(MockHttpServletResponse response) {
        assertFalse(response.isCommitted());
        assertEquals(0L, response.getContentLength());
        assertNull(response.getContentType());
    }
}
