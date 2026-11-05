/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一外部配置启动
 * @Version      : V1.0
 * @Description  : 验证受控服务配置目录选择与包内回退位置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.launch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class IwsConfigLocationSupportTest {

    @Test
    void selectsOnlyConfiguredServiceDirectory() {
        Path root = Path.of("C:/deploy with space/config").toAbsolutePath().normalize();

        Path selected = IwsConfigLocationSupport.selectServiceDirectory(root, "iws-a");

        assertEquals(root.resolve("iws-a"), selected);
        assertFalse(selected.equals(root.resolve("application.yml")));
    }

    @Test
    void rejectsEscapingConfigKey() {
        Path root = Path.of("C:/deploy/config").toAbsolutePath().normalize();

        assertThrows(
            IllegalArgumentException.class,
            () -> IwsConfigLocationSupport.selectServiceDirectory(root, "../prod")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> IwsConfigLocationSupport.resolveConfigKey(new String[0], "../prod")
        );
    }

    @Test
    void commandLineKeyWinsOverDefault() {
        String key = IwsConfigLocationSupport.resolveConfigKey(
            new String[] {"--iws.config.key=iws-b", "--spring.profiles.active=prod"},
            "iws"
        );

        assertEquals("iws-b", key);
    }

    @Test
    void explicitSharedRootMustBeAbsolute() {
        Path source = Path.of("C:/deploy/iws-admin.jar");

        assertThrows(
            IllegalArgumentException.class,
            () -> IwsConfigLocationSupport.resolveConfigRoot(
                new String[] {"--iws.config.root=relative/config"}, source
            )
        );
    }

    @Test
    void jarLocationsUseExternalGroupBeforeClasspathOnly() {
        Path config = Path.of("C:/deploy with space/config/iws-a").toAbsolutePath().normalize();
        String locations = IwsConfigLocationSupport.buildConfigLocations(
            config, Path.of("C:/deploy with space/iws-admin.jar")
        );

        assertTrue(locations.startsWith("optional:classpath:/;optional:file:"));
        assertTrue(locations.contains("/iws-a/"));
        assertTrue(locations.endsWith("/"));
    }

    @Test
    void developmentClassesUseOnlyClasspathDefault() {
        String locations = IwsConfigLocationSupport.buildConfigLocations(
            Path.of("C:/workspace/target/classes/config/iws"),
            Path.of("C:/workspace/target/classes")
        );

        assertEquals("optional:classpath:/", locations);
    }

    @Test
    void externalCommonPrecedesClasspathProfileButExternalProfilePrecedesBoth() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove("defaultProperties");
        sources.addLast(new MockPropertySource("system"));
        sources.addLast(new MockPropertySource(
            "Config resource 'file [C:\\deploy\\config\\iws-a\\application-prod.yml]' via location"
        ));
        sources.addLast(new MockPropertySource(
            "Config resource 'class path resource [application-prod.yml]' via location"
        ));
        sources.addLast(new MockPropertySource(
            "Config resource 'file [C:\\deploy\\config\\iws-a\\application.yml]' via location"
        ));
        sources.addLast(new MockPropertySource(
            "Config resource 'class path resource [application.yml]' via location"
        ));
        sources.addLast(new MockPropertySource("defaultProperties"));
        List<String> beforeReorder = new java.util.ArrayList<>();
        sources.forEach(source -> beforeReorder.add(source.getName()));
        assertEquals("system", beforeReorder.get(0));
        assertEquals("defaultProperties", beforeReorder.get(5));

        IwsConfigLocationSupport.reorderExternalFileSources(
            environment,
            Path.of("C:/deploy/config/iws-a"),
            Path.of("C:/deploy/iws-admin.jar")
        );

        List<String> names = new java.util.ArrayList<>();
        sources.forEach(source -> names.add(source.getName()));
        assertEquals("system", names.get(0), names.toString());
        assertTrue(names.get(1).contains("iws-a\\application-prod.yml"));
        assertTrue(names.get(2).contains("iws-a\\application.yml"));
        assertTrue(names.get(3).contains("class path resource [application-prod.yml]"));
        assertTrue(names.get(4).contains("class path resource [application.yml]"));
        assertEquals("defaultProperties", names.get(5));
    }
}
