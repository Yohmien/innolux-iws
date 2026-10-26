/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 与 endpoint 完整包扫描的模块装配矩阵；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.config;

import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.config.EndpointProperties;
import com.innolux.iws.endpoint.service.EndpointCatalogService;
import com.innolux.iws.process.runtime.EndpointTargetResolver;
import com.innolux.iws.process.service.PlanEventService;
import com.innolux.iws.processfixture.ProcessModuleAssemblyFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 以完整 process 包扫描验证关闭、独立 endpoint 与启动依赖缺失矩阵。
 *
 * <p>基础设施使用 mock，测试只创建 Spring 上下文，不调用 Mapper、JDBC 或真实业务方法。</p>
 */
@Tag("dev")
class ProcessFullScanConfigurationTest {

    private static final String PROCESS_PACKAGE = "com.innolux.iws.process.";

    private static final String ENDPOINT_PACKAGE = "com.innolux.iws.endpoint.";

    private static final String[] TEST_FIXTURE_CONFIGURATIONS = {
        "com.innolux.iws.process.config.ProcessModuleConfigurationTest",
        "com.innolux.iws.process.definition.DefinitionFixtureApplication",
        "com.innolux.iws.process.query.QueryFixtureApplication",
        "com.innolux.iws.process.runtime.RuntimeFixtureApplication",
        "com.innolux.iws.process.runtime.branch.BranchFixtureApplication",
        "com.innolux.iws.process.runtime.immediate.ImmediateFixtureApplication",
        "com.innolux.iws.process.runtime.recovery.RecoveryEvidenceMysqlFixture",
        "com.innolux.iws.process.supervisor.SupervisorFixtureApplication"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ProcessModuleAssemblyFixture.class);

    @Test
    void disabledModuleWithMissingEndpointJarRegistersNoProcessBusinessBeans() {
        runner.withClassLoader(withoutEndpointJar())
            .withPropertyValues("iws.process.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ProcessProperties.class);
                assertThat(processBusinessBeans(context)).isEmpty();
                assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
            });
    }

    @Test
    void endpointCanStartIndependentlyWhileProcessIsDisabled() {
        runner.withClassLoader(excludeTestFixtureConfigurations())
            .withPropertyValues("iws.process.enabled=false", "iws.endpoint.enabled=true",
                "iws.endpoint.current-environment=TEST")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(EndpointCatalogService.class)).hasSize(1);
                assertThat(context.getBeansOfType(EndpointProperties.class)).hasSize(1);
                assertThat(context.getBeansWithAnnotation(RestController.class)).hasSize(4);
                assertThat(context.getBean(EndpointProperties.class).getCurrentEnvironment())
                    .isEqualTo("TEST");
                assertThat(processBusinessBeans(context)).isEmpty();
            });
    }

    @Test
    void enabledModuleFailsClearlyWhenEndpointJarIsMissing() {
        runner.withClassLoader(withoutEndpointJar())
            .withPropertyValues("iws.process.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺少 iws-endpoint 模块或 EndpointCatalogReader Bean");
            });
    }

    @Test
    void enabledModuleFailsClearlyWhenEndpointReaderIsMissing() {
        runner.withClassLoader(excludeTestFixtureConfigurations())
            .withPropertyValues("iws.process.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context).getFailure()
                    .rootCause()
                    .hasMessageContaining("EndpointCatalogReader");
            });
    }

    @Test
    void enabledProcessAndEndpointScanAllBusinessComponentsWithoutDatabaseCalls() {
        EndpointCatalogReader reader = endpointReader();
        runner.withClassLoader(excludeTestFixtureConfigurations())
            .withPropertyValues("iws.process.enabled=true", "iws.endpoint.enabled=true",
                "iws.endpoint.current-environment=TEST",
                "iws.process.immediate.max-concurrency=1",
                "iws.process.immediate.response-reserve-ms=1")
            .withBean("processAssemblyEndpointCatalogReader", EndpointCatalogReader.class,
                () -> reader, definition -> definition.setPrimary(true))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(ProcessProperties.class)).hasSize(1);
                assertThat(context.getBeansOfType(ProcessConfiguration.ProcessDependencyGuard.class))
                    .hasSize(1);
                assertThat(context.getBeansOfType(PlanEventService.class)).hasSize(1);
                assertThat(context.getBeansOfType(EndpointTargetResolver.class)).hasSize(1);
                assertThat(context.getBeansOfType(EndpointCatalogService.class)).hasSize(1);
                assertThat(processBusinessBeans(context)).isNotEmpty();
                assertThat(context.getBeansWithAnnotation(RestController.class).values().stream()
                    .filter(controller -> controller.getClass().getName().startsWith(PROCESS_PACKAGE))
                    .count()).isEqualTo(7);
                assertThat(context.getBeansWithAnnotation(RestController.class).values().stream()
                    .filter(controller -> controller.getClass().getName().startsWith(ENDPOINT_PACKAGE))
                    .count()).isEqualTo(4);
                assertThat(org.mockito.Mockito.mockingDetails(context.getBean(JdbcTemplate.class)).getInvocations()
                    .stream().filter(invocation -> !invocation.getMethod().getName().equals("afterPropertiesSet")))
                    .isEmpty();
            });
    }

    private static EndpointCatalogReader endpointReader() {
        EndpointCatalogReader reader = mock(EndpointCatalogReader.class);
        when(reader.currentEnvironmentCode()).thenReturn("TEST");
        when(reader.snapshot()).thenReturn(new EndpointCatalogSnapshot("1", List.of(),
            List.of(new EndpointEnvironmentSnapshot("TEST", "隔离环境", true, "1")),
            List.of(), List.of()));
        return reader;
    }

    private static FilteredClassLoader excludeTestFixtureConfigurations() {
        return new FilteredClassLoader(TEST_FIXTURE_CONFIGURATIONS);
    }

    private static FilteredClassLoader withoutEndpointJar() {
        List<String> hidden = new ArrayList<>(List.of(TEST_FIXTURE_CONFIGURATIONS));
        hidden.add("com.innolux.iws.endpoint");
        return new FilteredClassLoader(hidden.toArray(String[]::new));
    }

    private static List<String> processBusinessBeans(AssertableApplicationContext context) {
        List<String> beans = new ArrayList<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getBeanFactory().getType(beanName);
            if (type == null) {
                continue;
            }
            String className = type.getName();
            if (className.startsWith(PROCESS_PACKAGE + "controller.")
                || className.startsWith(PROCESS_PACKAGE + "service.")
                || className.startsWith(PROCESS_PACKAGE + "runtime.")) {
                beans.add(beanName + "=" + className);
            }
        }
        return beans;
    }
}
