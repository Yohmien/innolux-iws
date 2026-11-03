/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint 启停、权限与公开契约测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReaderImpl;
import com.innolux.iws.endpoint.config.EndpointProperties;
import com.innolux.iws.endpoint.controller.EndpointAddressController;
import com.innolux.iws.endpoint.controller.EndpointAuditController;
import com.innolux.iws.endpoint.controller.EndpointEnvironmentController;
import com.innolux.iws.endpoint.controller.EndpointSystemController;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemAddressBo;
import com.innolux.iws.endpoint.repository.EndpointCatalogRepository;
import com.innolux.iws.endpoint.service.EndpointCatalogService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * endpoint 启停、权限与公开契约测试。
 */
@Tag("dev")
class EndpointModuleContractTest {

    @Test
    void disabledOrMissingSwitchRegistersNoEndpointBeans() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EndpointPackageScan.class);

        runner.run(context -> {
            assertThat(context).doesNotHaveBean(EndpointCatalogReader.class);
            assertThat(context).doesNotHaveBean(EndpointCatalogService.class);
            assertThat(context).doesNotHaveBean(EndpointCatalogRepository.class);
            assertThat(context).doesNotHaveBean(EndpointSystemController.class);
            assertThat(context).doesNotHaveBean(EndpointProperties.class);
        });
        runner.withPropertyValues("iws.endpoint.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(EndpointCatalogReader.class);
            assertThat(context).doesNotHaveBean(EndpointSystemController.class);
        });
    }

    @Test
    void allBusinessBeansDeclareEnableSwitch() {
        for (Class<?> type : List.of(EndpointCatalogReaderImpl.class, EndpointCatalogService.class,
            EndpointCatalogRepository.class, EndpointProperties.class, EndpointSystemController.class,
            EndpointEnvironmentController.class, EndpointAddressController.class, EndpointAuditController.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition).as(type.getSimpleName()).isNotNull();
            assertThat(condition.prefix()).isEqualTo("iws.endpoint");
            assertThat(condition.name()).containsExactly("enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
        }
    }

    @Test
    void systemAddressReplaceRequiresEditAndAddressQuery() throws Exception {
        Method method = EndpointSystemController.class.getMethod("replaceAddresses", String.class,
            EndpointSystemAddressBo.class);
        SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("endpoint:system:edit", "endpoint:address:query");
        assertThat(permission.mode()).isEqualTo(SaMode.AND);
    }

    /**
     * 仅用于验证关闭开关时组件扫描结果。
     */
    @Configuration
    @ComponentScan(basePackageClasses = {
        EndpointCatalogReaderImpl.class,
        EndpointCatalogService.class,
        EndpointCatalogRepository.class,
        EndpointProperties.class,
        EndpointSystemController.class
    })
    static class EndpointPackageScan {
    }
}
