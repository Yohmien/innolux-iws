/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一外部配置启动
 * @Version      : V1.0
 * @Description  : 以运行 JAR 目录为基准装配外部配置目录并保留包内配置；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.launch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 统一装配 JAR 同目录 config 与包内 application 配置。
 */
public final class IwsConfigLocationSupport {

    private static final Logger LOG = LoggerFactory.getLogger(IwsConfigLocationSupport.class);

    private static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

    private static final String CONFIG_ROOT_PROPERTY = "iws.config.root";

    private static final String CONFIG_KEY_PROPERTY = "iws.config.key";

    private static final Pattern CONFIG_KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private IwsConfigLocationSupport() {
    }

    /**
     * 在 Spring Config Data 读取前限制配置搜索范围。
     *
     * @param application      待启动应用
     * @param executableClass  可执行应用入口类，用于定位实际运行 JAR
     * @param args             原始启动参数
     * @param defaultConfigKey 制品默认配置目录名
     */
    public static void configure(
        SpringApplication application,
        Class<?> executableClass,
        String[] args,
        String defaultConfigKey
    ) {
        Path applicationSource = resolveApplicationSource(executableClass);
        String configKey = resolveConfigKey(args, defaultConfigKey);
        Path configRoot = resolveConfigRoot(args, applicationSource);
        Path configDirectory = selectServiceDirectory(configRoot, configKey);
        String locations = buildConfigLocations(configDirectory, applicationSource);
        application.setDefaultProperties(Map.of(
            CONFIG_LOCATION_PROPERTY, locations,
            CONFIG_ROOT_PROPERTY, configRoot.toString(),
            CONFIG_KEY_PROPERTY, configKey
        ));
        application.addListeners(event -> {
            if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
                reorderExternalFileSources(prepared.getEnvironment(), configDirectory, applicationSource);
                logPreparedEnvironment(
                    configRoot, configKey, configDirectory, applicationSource, prepared.getEnvironment()
                );
            }
        });
    }

    /**
     * 构建外部优先、包内回退的 Config Data 位置。
     *
     * @param configDirectory JAR 同目录 config
     * @param applicationSource 运行 JAR 或开发类目录
     * @return Config Data 位置串
     */
    static String buildConfigLocations(Path configDirectory, Path applicationSource) {
        List<String> groups = new ArrayList<>(2);
        groups.add("optional:classpath:/");
        if (isExecutableJar(applicationSource)) {
            groups.add("optional:" + directoryLocation(configDirectory));
        }
        return String.join(";", groups);
    }

    /**
     * 解析受控配置目录键，命令行优先于系统属性、环境变量和制品默认值。
     *
     * @param args 启动参数
     * @param defaultConfigKey 制品默认键
     * @return 单级配置目录键
     */
    static String resolveConfigKey(String[] args, String defaultConfigKey) {
        String value = resolveMetaValue(args, CONFIG_KEY_PROPERTY, "IWS_CONFIG_KEY", defaultConfigKey);
        if (value == null || !CONFIG_KEY_PATTERN.matcher(value).matches()
            || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("iws.config.key 必须是受控单级目录名");
        }
        return value;
    }

    /**
     * 解析共享配置根目录；显式根目录必须是绝对路径。
     *
     * @param args 启动参数
     * @param applicationSource 运行 JAR 或开发类目录
     * @return 规范化绝对根目录
     */
    static Path resolveConfigRoot(String[] args, Path applicationSource) {
        String value = resolveMetaValue(args, CONFIG_ROOT_PROPERTY, "IWS_CONFIG_ROOT", null);
        if (value == null || value.isBlank()) {
            Path parent = isExecutableJar(applicationSource)
                ? applicationSource.getParent()
                : applicationSource;
            if (parent == null) {
                throw new IllegalStateException("运行 JAR 缺少父目录: " + applicationSource);
            }
            return parent.resolve("config").toAbsolutePath().normalize();
        }
        Path root = Path.of(value);
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("iws.config.root 必须使用绝对路径");
        }
        return root.normalize();
    }

    /**
     * 从根目录选择且只选择一个服务配置目录。
     *
     * @param configRoot 配置根目录
     * @param configKey 受控单级键
     * @return 服务配置目录
     */
    static Path selectServiceDirectory(Path configRoot, String configKey) {
        Path selected = configRoot.resolve(configKey).normalize();
        if (!selected.startsWith(configRoot)) {
            throw new IllegalArgumentException("iws.config.key 不能逃逸配置根目录");
        }
        return selected;
    }

    /**
     * 定位启动入口所在的 JAR 或开发类目录。
     *
     * @param executableClass 可执行应用入口类
     * @return 绝对路径
     */
    static Path resolveApplicationSource(Class<?> executableClass) {
        File source = new ApplicationHome(executableClass).getSource();
        if (source == null) {
            throw new IllegalStateException("无法定位运行 JAR 或开发类目录: " + executableClass.getName());
        }
        return source.toPath().toAbsolutePath().normalize();
    }

    private static boolean isExecutableJar(Path source) {
        return source.getFileName() != null
            && source.getFileName().toString().toLowerCase().endsWith(".jar");
    }

    private static String directoryLocation(Path directory) {
        URI uri = directory.toUri();
        String value = uri.toString();
        return value.endsWith("/") ? value : value + "/";
    }

    private static String resolveMetaValue(
        String[] args,
        String propertyName,
        String environmentName,
        String defaultValue
    ) {
        String commandLineValue = readCommandLineValue(args, propertyName);
        if (commandLineValue != null) {
            return commandLineValue;
        }
        String systemValue = System.getProperty(propertyName);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        return defaultValue;
    }

    private static String readCommandLineValue(String[] args, String propertyName) {
        if (args == null) {
            return null;
        }
        String prefix = "--" + propertyName + "=";
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
            if (("--" + propertyName).equals(arg) && index + 1 < args.length
                && !args[index + 1].startsWith("--")) {
                return args[index + 1];
            }
        }
        return null;
    }

    private static void logPreparedEnvironment(
        Path configRoot,
        String configKey,
        Path configDirectory,
        Path applicationSource,
        ConfigurableEnvironment environment
    ) {
        List<String> sources = environment.getPropertySources().stream()
            .map(propertySource -> propertySource.getName())
            .filter(name -> name.contains("application") || name.startsWith("Config resource"))
            .toList();
        LOG.info(
            "配置来源已装配: executable={}, configRoot={}, configKey={}, serviceConfig={}, "
                + "externalDirectoryPresent={}, profiles={}, sources={}",
            applicationSource,
            configRoot,
            configKey,
            configDirectory,
            Files.isDirectory(configDirectory),
            List.of(environment.getActiveProfiles()),
            sources
        );
    }

    /**
     * 将所选服务的外部普通配置置于包内 profile 配置之前，同时保持外部 profile 优先。
     *
     * @param environment      已加载 Config Data 的环境
     * @param configDirectory  所选服务配置目录
     * @param applicationSource 运行 JAR 或开发类目录
     */
    static void reorderExternalFileSources(
        ConfigurableEnvironment environment,
        Path configDirectory,
        Path applicationSource
    ) {
        if (!isExecutableJar(applicationSource)) {
            return;
        }
        MutablePropertySources propertySources = environment.getPropertySources();
        List<PropertySource<?>> current = new ArrayList<>();
        propertySources.forEach(current::add);

        List<String> activeProfiles = List.of(environment.getActiveProfiles());
        String externalDirectory = configDirectory.toAbsolutePath().normalize().toString();
        List<PropertySource<?>> externalProfiles = current.stream()
            .filter(source -> isExternalProfileSource(source, externalDirectory, activeProfiles))
            .toList();
        List<PropertySource<?>> externalCommon = current.stream()
            .filter(source -> isExternalCommonSource(source, externalDirectory))
            .toList();
        List<PropertySource<?>> classpathProfiles = current.stream()
            .filter(source -> isClasspathProfileSource(source, activeProfiles))
            .toList();
        List<PropertySource<?>> classpathCommon = current.stream()
            .filter(IwsConfigLocationSupport::isClasspathCommonSource)
            .toList();
        List<PropertySource<?>> selected = new ArrayList<>();
        selected.addAll(externalProfiles);
        selected.addAll(externalCommon);
        selected.addAll(classpathProfiles);
        selected.addAll(classpathCommon);

        List<PropertySource<?>> remainingConfig = current.stream()
            .filter(source -> source.getName().startsWith("Config resource"))
            .filter(source -> !selected.contains(source))
            .toList();
        List<PropertySource<?>> reordered = new ArrayList<>(current.size());
        boolean configBlockInserted = false;
        for (PropertySource<?> source : current) {
            if (!source.getName().startsWith("Config resource")) {
                reordered.add(source);
                continue;
            }
            if (!configBlockInserted) {
                reordered.addAll(selected);
                reordered.addAll(remainingConfig);
                configBlockInserted = true;
            }
        }

        for (PropertySource<?> source : current) {
            propertySources.remove(source.getName());
        }
        for (PropertySource<?> source : reordered) {
            propertySources.addLast(source);
        }
    }

    private static boolean isExternalProfileSource(
        PropertySource<?> source,
        String externalDirectory,
        List<String> activeProfiles
    ) {
        String name = source.getName();
        return name.startsWith("Config resource")
            && name.contains(externalDirectory)
            && activeProfiles.stream().anyMatch(profile ->
                name.contains("application-" + profile + ".yml")
                    || name.contains("application-" + profile + ".yaml")
                    || name.contains("application-" + profile + ".properties")
            );
    }

    private static boolean isExternalCommonSource(PropertySource<?> source, String externalDirectory) {
        String name = source.getName();
        return name.startsWith("Config resource")
            && name.contains(externalDirectory)
            && (name.contains("application.yml")
                || name.contains("application.yaml")
                || name.contains("application.properties"));
    }

    private static boolean isClasspathProfileSource(
        PropertySource<?> source,
        List<String> activeProfiles
    ) {
        String name = source.getName();
        return name.startsWith("Config resource")
            && name.contains("class path resource [application-")
            && activeProfiles.stream().anyMatch(profile -> name.contains(profile + ".yml")
                || name.contains(profile + ".yaml")
                || name.contains(profile + ".properties"));
    }

    private static boolean isClasspathCommonSource(PropertySource<?> source) {
        String name = source.getName();
        return name.startsWith("Config resource")
            && (name.contains("class path resource [application.yml]")
                || name.contains("class path resource [application.yaml]")
                || name.contains("class path resource [application.properties]"));
    }
}
