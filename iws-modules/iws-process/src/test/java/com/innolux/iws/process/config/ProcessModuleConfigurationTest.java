package com.innolux.iws.process.config;

import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证部署开关、缺失依赖与当前环境门禁，不连接数据库。 */
@Tag("dev")
class ProcessModuleConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ConfigurationScan.class);

    @Test
    void disabledModuleRegistersNoProcessBeansEvenWhenEndpointJarIsMissing() {
        runner.withClassLoader(new FilteredClassLoader("com.innolux.iws.endpoint"))
            .withPropertyValues("iws.process.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("processDependencyGuard");
                assertThat(context).doesNotHaveBean("missingEndpointGuard");
                assertThat(context).doesNotHaveBean(ProcessProperties.class);
            });
    }

    @Test
    void enabledModuleFailsClearlyWhenEndpointJarIsMissing() {
        runner.withClassLoader(new FilteredClassLoader("com.innolux.iws.endpoint"))
            .withPropertyValues("iws.process.enabled=true")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledModuleRequiresReaderBean() {
        runner.withPropertyValues("iws.process.enabled=true")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void currentEnvironmentMustBeConfiguredAndEnabled() {
        for (String environment : new String[] {"", "MISSING", "DISABLED"}) {
            withReader(environment).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void validEnvironmentEnablesGuardAndBindsNestedSwitches() {
        withReader("TEST").withPropertyValues("iws.process.scheduler.enabled=true",
                "iws.process.workflow.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("processDependencyGuard");
                ProcessProperties properties = context.getBean(ProcessProperties.class);
                assertThat(properties.getScheduler().isEnabled()).isTrue();
                assertThat(properties.getWorkflow().isEnabled()).isFalse();
            });
    }

    private ApplicationContextRunner withReader(String environment) {
        EndpointCatalogReader reader = mock(EndpointCatalogReader.class);
        when(reader.currentEnvironmentCode()).thenReturn(environment);
        when(reader.snapshot()).thenReturn(new EndpointCatalogSnapshot("0", List.of(),
            List.of(new EndpointEnvironmentSnapshot("TEST", "隔离环境", true, "1"),
                new EndpointEnvironmentSnapshot("DISABLED", "停用环境", false, "1")),
            List.of(), List.of()));
        return runner.withPropertyValues("iws.process.enabled=true")
            .withBean(EndpointCatalogReader.class, () -> reader);
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ProcessConfiguration.class)
    static class ConfigurationScan {
    }
}
