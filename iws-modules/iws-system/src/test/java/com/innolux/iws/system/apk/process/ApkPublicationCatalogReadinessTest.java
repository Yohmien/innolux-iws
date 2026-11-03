/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : 验证 APK 发布对当前环境与 IWS 系统目录的明确前置提示
 * @ModifyRecord : V1.0 新建
 */

package com.innolux.iws.system.apk.process;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * APK 发布目录前置校验测试。
 */
@DisplayName("APK 发布目录前置校验")
@Tag("dev")
class ApkPublicationCatalogReadinessTest {

    @Test
    @DisplayName("当前环境配置为空时明确提示配置项")
    void rejectsBlankCurrentEnvironment() {
        ServiceException error = assertThrows(ServiceException.class,
            () -> ApkPublicationProcess.validateCatalogReady(snapshot(true, true), " "));

        assertTrue(error.getMessage().contains("iws.endpoint.current-environment"));
    }

    @Test
    @DisplayName("当前环境不存在时提示环境配置页面")
    void rejectsMissingCurrentEnvironment() {
        ServiceException error = assertThrows(ServiceException.class,
            () -> ApkPublicationProcess.validateCatalogReady(snapshot(true, true), "PROD"));

        assertTrue(error.getMessage().contains("当前接入环境不存在：PROD"));
        assertTrue(error.getMessage().contains("环境配置"));
    }

    @Test
    @DisplayName("当前环境停用时明确提示启用")
    void rejectsDisabledCurrentEnvironment() {
        ServiceException error = assertThrows(ServiceException.class,
            () -> ApkPublicationProcess.validateCatalogReady(snapshot(false, true), "TEST"));

        assertTrue(error.getMessage().contains("当前接入环境未启用：TEST"));
    }

    @Test
    @DisplayName("缺少 IWS 系统时提示系统定义页面")
    void rejectsMissingSelfSystem() {
        EndpointCatalogSnapshot snapshot = new EndpointCatalogSnapshot("7",
            List.of(),
            List.of(new EndpointEnvironmentSnapshot("TEST", "测试环境", true, "3")),
            List.of(),
            List.of());

        ServiceException error = assertThrows(ServiceException.class,
            () -> ApkPublicationProcess.validateCatalogReady(snapshot, "TEST"));

        assertTrue(error.getMessage().contains("缺少系统定义 IWS"));
        assertTrue(error.getMessage().contains("系统定义"));
    }

    @Test
    @DisplayName("IWS 系统停用时明确提示启用")
    void rejectsDisabledSelfSystem() {
        ServiceException error = assertThrows(ServiceException.class,
            () -> ApkPublicationProcess.validateCatalogReady(snapshot(true, false), "TEST"));

        assertTrue(error.getMessage().contains("系统 IWS 未启用"));
    }

    @Test
    @DisplayName("当前环境和 IWS 系统均启用时通过")
    void acceptsReadyCatalog() {
        assertDoesNotThrow(() -> ApkPublicationProcess.validateCatalogReady(snapshot(true, true), "TEST"));
    }

    private static EndpointCatalogSnapshot snapshot(boolean environmentEnabled, boolean systemEnabled) {
        return new EndpointCatalogSnapshot("7",
            List.of(new EndpointSystemSnapshot("IWS", "IWS+", systemEnabled, "4")),
            List.of(new EndpointEnvironmentSnapshot("TEST", "测试环境", environmentEnabled, "3")),
            List.of(),
            List.of());
    }
}
