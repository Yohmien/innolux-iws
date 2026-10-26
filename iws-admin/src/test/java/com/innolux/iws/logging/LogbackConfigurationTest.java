/*
 * @Date         : 2026-10-26
 * @Author       : Codex
 * @Job          : IWS 日志汇总与模块分流验证
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 隔离验证日志级别覆盖、模块路由及 50MB 日期滚动；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedFileNamingAndTriggeringPolicy;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
@DisplayName("日志汇总与模块分流")
class LogbackConfigurationTest {

    private static final long FIFTY_MEGABYTES = 50L * 1024 * 1024;
    private static final int MESSAGE_BYTES = 64 * 1024;

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("所有配置采用 50MB 日期滚动且不再按级别过滤")
    void allConfigurationsUseSizeAndTimeRollingWithoutLevelFilters() throws IOException {
        Path root = Path.of("..");
        List<Path> configurations = List.of(
            Path.of("src", "main", "resources", "logback-plus.xml"),
            root.resolve("iws-extend/iws-snailjob-server/src/main/resources/logback-plus.xml"),
            root.resolve("iws-extend/iws-snailai-server/src/main/resources/logback-plus.xml"),
            root.resolve("iws-extend/iws-monitor-admin/src/main/resources/logback-plus.xml")
        );

        for (Path configuration : configurations) {
            String xml = Files.readString(configuration, StandardCharsets.UTF_8);
            assertTrue(xml.contains("SizeAndTimeBasedRollingPolicy"), configuration.toString());
            assertTrue(xml.contains("${LOG_ROOT:-./logs}"), configuration.toString());
            assertTrue(xml.contains("${LOG_MAX_FILE_SIZE:-50MB}"), configuration.toString());
            assertTrue(xml.contains("%d{yyyy-MM-dd}.%i.log.gz"), configuration.toString());
            assertFalse(xml.contains("LevelFilter"), configuration.toString());
            assertFalse(xml.contains("ThresholdFilter"), configuration.toString());
        }
    }

    @ParameterizedTest(name = "{1} WARN 写入本服务 all.log")
    @CsvSource({
        "src/main/resources/logback-plus.xml, iws-admin",
        "../iws-extend/iws-snailjob-server/src/main/resources/logback-plus.xml, iws-snailjob-server",
        "../iws-extend/iws-snailai-server/src/main/resources/logback-plus.xml, iws-snailai-server",
        "../iws-extend/iws-monitor-admin/src/main/resources/logback-plus.xml, iws-monitor-admin"
    })
    void everyServiceWarnsToItsOwnSummaryLog(String configuration, String serviceDirectory) throws Exception {
        Path logRoot = tempDirectory.resolve(serviceDirectory);
        String marker = "warn-marker-" + serviceDirectory;
        LoggerContext context = loadConfiguration(Path.of(configuration), logRoot);
        try {
            logger(context, "com.innolux.iws.fixture.LogFixture").warn(marker);
        } finally {
            context.stop();
        }

        String allLog = Files.readString(logRoot.resolve(serviceDirectory).resolve("all.log"), StandardCharsets.UTF_8);
        assertTrue(allLog.contains(marker));
    }

    @Test
    @DisplayName("主服务汇总全部已启用级别并按源码模块分流")
    void mainServiceRoutesEnabledLevelsToSummaryAndModules() throws Exception {
        Path logRoot = tempDirectory.resolve("logs");
        LoggerContext context = loadConfiguration(Path.of("src/main/resources/logback-plus.xml"), logRoot);
        try {
            logger(context, "com.innolux.iws.wms.WmsService").info("wms-info-marker");
            logger(context, "com.innolux.iws.system.SystemService").warn("system-warn-marker");
            logger(context, "com.innolux.iws.process.ProcessService")
                .error("process-error-marker", new IllegalStateException("process-stack-marker"));
            logger(context, "com.innolux.iws.unknown.UnknownService").info("application-marker");
            logger(context, "SQL_FULL").info("sql-info-marker");
            logger(context, "org.springframework.FrameworkService").error("framework-error-marker");
        } finally {
            context.stop();
        }

        Path serviceDirectory = logRoot.resolve("iws-admin");
        String allLog = Files.readString(serviceDirectory.resolve("all.log"), StandardCharsets.UTF_8);
        assertTrue(allLog.contains("wms-info-marker"));
        assertTrue(allLog.contains("system-warn-marker"));
        assertTrue(allLog.contains("process-error-marker"));
        assertTrue(allLog.contains("process-stack-marker"));
        assertTrue(allLog.contains("sql-info-marker"));
        assertTrue(allLog.contains("framework-error-marker"));

        Path modules = serviceDirectory.resolve("modules");
        assertTrue(Files.readString(modules.resolve("wms.log"), StandardCharsets.UTF_8).contains("wms-info-marker"));
        assertTrue(Files.readString(modules.resolve("system.log"), StandardCharsets.UTF_8).contains("system-warn-marker"));
        assertTrue(Files.readString(modules.resolve("process.log"), StandardCharsets.UTF_8).contains("process-stack-marker"));
        assertTrue(Files.readString(modules.resolve("application.log"), StandardCharsets.UTF_8).contains("application-marker"));
        assertTrue(Files.readString(modules.resolve("common.log"), StandardCharsets.UTF_8).contains("sql-info-marker"));
        try (Stream<Path> files = Files.list(modules)) {
            assertFalse(files.anyMatch(path -> readQuietly(path).contains("framework-error-marker")));
        }
    }

    @Test
    @DisplayName("实际写入超过 50MB 后按日期和序号滚动")
    void rollsActualSummaryLogAfterFiftyMegabytes() throws Exception {
        Path logRoot = tempDirectory.resolve("logs");
        LoggerContext context = loadConfiguration(Path.of("src/main/resources/logback-plus.xml"), logRoot);
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.detachAppender("console");
        String payload = "r".repeat(MESSAGE_BYTES);
        try {
            Logger largeLogger = logger(context, "org.example.LargeLogFixture");
            for (int i = 0; i < 900; i++) {
                largeLogger.info("rollover-{}", payload);
            }
        } finally {
            context.stop();
        }

        Path serviceDirectory = logRoot.resolve("iws-admin");
        String date = ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate().toString();
        Path archive;
        try (Stream<Path> files = Files.list(serviceDirectory)) {
            archive = files
                .filter(path -> path.getFileName().toString().matches("all\\." + date + "\\.\\d+\\.log\\.gz"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未生成带日期和序号的 all.log 归档"));
        }

        long archivedBytes = uncompressedSize(archive);
        assertTrue(archivedBytes > FIFTY_MEGABYTES, "归档应包含超过 50MB 的原始日志");
        assertTrue(archivedBytes <= FIFTY_MEGABYTES + MESSAGE_BYTES + 1024,
            "滚动按事件边界触发，允许单条事件越过阈值");
        assertTrue(Files.size(archive) < archivedBytes);
        long activeBytes = Files.size(serviceDirectory.resolve("all.log"));
        assertTrue(activeBytes < FIFTY_MEGABYTES);
        System.out.printf("[IWS-LOG] size-rollover archive=%s rawBytes=%d compressedBytes=%d activeBytes=%d%n",
            archive.getFileName(), archivedBytes, Files.size(archive), activeBytes);
    }

    @Test
    @DisplayName("注入日期时钟后按日期周期滚动")
    void rollsAfterInjectedDateAdvance() throws Exception {
        Path logRoot = tempDirectory.resolve("logs");
        LoggerContext context = loadConfiguration(Path.of("src/main/resources/logback-plus.xml"), logRoot);
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime firstDay = ZonedDateTime.now(zoneId);
        ZonedDateTime secondDay = firstDay.plusDays(1);
        ZonedDateTime thirdDay = firstDay.plusDays(2);
        try {
            Logger fixture = logger(context, "org.example.DateRolloverFixture");
            fixture.info("first-day-marker");
            triggeringPolicy(context, "file_all").setCurrentTime(secondDay.toInstant().toEpochMilli());
            fixture.info("second-day-marker");
            triggeringPolicy(context, "file_all").setCurrentTime(thirdDay.toInstant().toEpochMilli());
            fixture.info("third-day-marker");
        } finally {
            context.stop();
        }

        Path secondDayArchive = logRoot.resolve("iws-admin").resolve(
            "all." + secondDay.toLocalDate() + ".0.log.gz");
        assertTrue(Files.exists(secondDayArchive), "第二个注入日期应发生日期滚动");
        String archived = readGzip(secondDayArchive);
        assertTrue(archived.contains("second-day-marker"));
        assertFalse(archived.contains("third-day-marker"));
        boolean activeContainsThird = Files.readString(logRoot.resolve("iws-admin/all.log"), StandardCharsets.UTF_8)
            .contains("third-day-marker");
        assertTrue(activeContainsThird);
        System.out.printf("[IWS-LOG] date-rollover archive=%s compressedBytes=%d activeContainsThird=%s%n",
            secondDayArchive.getFileName(), Files.size(secondDayArchive), activeContainsThird);
    }

    private LoggerContext loadConfiguration(Path configuration, Path logRoot) throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.putProperty("LOG_ROOT", logRoot.toAbsolutePath().toString());
        context.start();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(configuration.toFile());
        List<String> errors = context.getStatusManager().getCopyOfStatusList().stream()
            .filter(status -> status.getLevel() == Status.ERROR)
            .map(Status::toString)
            .toList();
        assertTrue(errors.isEmpty(), () -> String.join(System.lineSeparator(), errors));
        return context;
    }

    private Logger logger(LoggerContext context, String name) {
        return context.getLogger(name);
    }

    private String readQuietly(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private long uncompressedSize(Path compressed) throws IOException {
        long size = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = new GZIPInputStream(Files.newInputStream(compressed))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
            }
        }
        return size;
    }

    private String readGzip(Path compressed) throws IOException {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(compressed))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private TimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent> triggeringPolicy(
        LoggerContext context, String appenderName) {
        RollingFileAppender<ILoggingEvent> appender = assertInstanceOf(
            RollingFileAppender.class, context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(appenderName));
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = assertInstanceOf(
            SizeAndTimeBasedRollingPolicy.class, appender.getRollingPolicy());
        return policy.getTimeBasedFileNamingAndTriggeringPolicy();
    }
}
