/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 验证 APK 发布端口同事务、幂等和失败安全；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.system.apk.domain.ApkRelease;
import com.innolux.iws.system.apk.mapper.ApkReleaseMapper;
import com.innolux.iws.system.apk.process.ApkPublicationFact;
import com.innolux.iws.system.apk.service.impl.ApkReleasePublicationPortImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

/**
 * APK 发布端口测试。
 */
@DisplayName("APK 发布端口")
@Tag("dev")
class ApkReleasePublicationPortImplTest {

    @BeforeAll
    static void initializeMybatis() {
        ApkMybatisTestSupport.initialize();
    }

    @Test
    @DisplayName("端口强制加入调用方事务")
    void requiresExistingTransaction() throws NoSuchMethodException {
        Transactional transactional = ApkReleasePublicationPortImpl.class
            .getMethod("publish", long.class, long.class).getAnnotation(Transactional.class);

        assertEquals(Propagation.MANDATORY, transactional.propagation());
    }

    @Test
    @DisplayName("待发布版本写入 PUBLISHED 和计划实例 ID")
    void publishesUploadedRelease() {
        ApkReleaseMapper mapper = mock(ApkReleaseMapper.class);
        ApkRelease release = release("UPLOADED", null);
        when(mapper.selectById(10L)).thenReturn(release);
        when(mapper.update(isNull(), any())).thenReturn(1);
        ApkReleasePublicationPortImpl port = new ApkReleasePublicationPortImpl(mapper);

        port.publish(10L, 20L);

        ArgumentCaptor<Wrapper<ApkRelease>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), wrapperCaptor.capture());
        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("status"));
        assertTrue(sqlSet.contains("process_instance_id"));
    }

    @Test
    @DisplayName("同一计划实例重复发布保持幂等")
    void acceptsSameInstanceRepeat() {
        ApkReleaseMapper mapper = mock(ApkReleaseMapper.class);
        when(mapper.selectById(10L)).thenReturn(release("PUBLISHED", 20L));
        ApkReleasePublicationPortImpl port = new ApkReleasePublicationPortImpl(mapper);

        assertDoesNotThrow(() -> port.publish(10L, 20L));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("其他计划实例不能重复发布")
    void rejectsDifferentInstance() {
        ApkReleaseMapper mapper = mock(ApkReleaseMapper.class);
        when(mapper.selectById(10L)).thenReturn(release("PUBLISHED", 21L));
        ApkReleasePublicationPortImpl port = new ApkReleasePublicationPortImpl(mapper);

        assertThrows(ServiceException.class, () -> port.publish(10L, 20L));
    }

    @Test
    @DisplayName("条件更新失败且仍待发布时抛错，不伪造成功")
    void failsWhenConditionalUpdateLoses() {
        ApkReleaseMapper mapper = mock(ApkReleaseMapper.class);
        ApkRelease uploaded = release("UPLOADED", null);
        when(mapper.selectById(10L)).thenReturn(uploaded, uploaded);
        when(mapper.update(isNull(), any())).thenReturn(0);
        ApkReleasePublicationPortImpl port = new ApkReleasePublicationPortImpl(mapper);

        assertThrows(ServiceException.class, () -> port.publish(10L, 20L));
    }

    @Test
    @DisplayName("按计划实例读取已发布事实")
    void findsPublicationFactByInstance() {
        ApkReleaseMapper mapper = mock(ApkReleaseMapper.class);
        Page<ApkRelease> page = new Page<>(1, 2);
        page.setRecords(List.of(release("PUBLISHED", 20L)));
        when(mapper.selectPage(any(Page.class), any())).thenReturn(page);
        ApkReleasePublicationPortImpl port = new ApkReleasePublicationPortImpl(mapper);

        Optional<ApkPublicationFact> fact = port.findByPlanInstanceId(20L);

        assertTrue(fact.isPresent());
        assertEquals(10L, fact.get().releaseId());
        assertEquals(20L, fact.get().planInstanceId());
        assertTrue(fact.get().published());
    }

    private static ApkRelease release(String status, Long instanceId) {
        ApkRelease release = new ApkRelease();
        release.setReleaseId(10L);
        release.setStatus(status);
        release.setProcessInstanceId(instanceId);
        return release;
    }
}
